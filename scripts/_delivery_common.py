#!/usr/bin/env python3
# @author kongweiguang
"""共享交付脚本的参数和外部命令约定。"""

from __future__ import annotations

import argparse
import os
import signal
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Sequence


class ChineseHelpFormatter(argparse.HelpFormatter):
    """统一维护脚本的中文用法标题，同时保留 argparse 的自动排版。"""

    def add_usage(self, usage, actions, groups, prefix=None):
        """使用中文标题展示用法，避免不同脚本帮助语言不一致。"""
        return super().add_usage(usage, actions, groups, prefix or "用法：")


class ChineseArgumentParser(argparse.ArgumentParser):
    """将选项分组和错误提示本地化，降低跨平台命令输入成本。"""

    def __init__(self, *args, **kwargs):
        """保留 argparse 的校验与退出码，只调整用户可见文本。"""
        kwargs.setdefault("add_help", False)
        super().__init__(*args, **kwargs)
        self._optionals.title = "选项"
        self.add_argument("-h", "--help", action="help", help="显示此帮助信息并退出。")

    def error(self, message: str) -> None:
        """以中文提示无效参数，并继续使用标准的参数错误退出码。"""
        self.print_usage(sys.stderr)
        self.exit(2, f"{self.prog}: 错误：{message}\n")


def windows_batch_command(executable: str, *, windows: bool | None = None) -> bool:
    """识别必须交给 Windows 系统 shell 启动的批处理入口。"""
    is_windows = os.name == "nt" if windows is None else windows
    return is_windows and Path(executable).suffix.casefold() in (".cmd", ".bat")


def command_argv(executable: str, arguments: Sequence[str], *, windows: bool | None = None) -> list[str]:
    """保留参数数组；对批处理入口拒绝 cmd 的百分号环境变量展开语法。"""
    if windows_batch_command(executable, windows=windows) and any("%" in token for token in (executable, *arguments)):
        raise ValueError("Windows 批处理命令参数不能包含 '%'，以避免 cmd.exe 环境变量展开。")
    return [executable, *arguments]


def resolve_executable(value: str) -> str:
    """解析显式路径或 PATH 命令，确保后续子进程不依赖 shell 搜索。"""
    path = Path(value).expanduser()
    if path.is_file():
        return str(path.resolve())
    resolved = shutil.which(value)
    if resolved is None:
        raise FileNotFoundError(f"找不到可执行命令：{value}")
    return str(Path(resolved).resolve())


class CommandTimeoutError(RuntimeError):
    """表示命令超时且其专属进程树已被回收。"""

    def __init__(self, message: str, output: str = "") -> None:
        """保留超时前已输出的诊断，避免清理子进程时丢失构建线索。"""
        super().__init__(message)
        self.output = output


def _signal_process_group(process: subprocess.Popen[str], signum: int, deadline: float) -> bool:
    """先回收直属僵尸；Darwin 的僵尸组可能暂报 EPERM，但持续权限错误不能当成退出。"""
    while True:
        process.poll()
        try:
            os.killpg(process.pid, signum)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            if process.poll() is None:
                raise
            # 根进程可能恰在 poll 与 killpg 之间退出；回收后立即重查，不能据根退出推断整组退出。
            try:
                os.killpg(process.pid, signum)
                return True
            except ProcessLookupError:
                return False
            except PermissionError:
                # 非直属僵尸由系统回收；只在同一清理期限内等待，真正权限拒绝最终仍显式失败。
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise
                time.sleep(min(0.05, remaining))


def stop_process_tree(process: subprocess.Popen[str]) -> None:
    """仅清理本次进程组；回收根进程后仍检查后代，共用有界宽限且不掩盖权限失败。"""
    if os.name == "nt":
        system_root = Path(os.environ.get("SystemRoot", r"C:\Windows"))
        taskkill = system_root / "System32" / "taskkill.exe"
        try:
            subprocess.run(
                [str(taskkill), "/PID", str(process.pid), "/T", "/F"],
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=10,
                check=False,
                creationflags=subprocess.CREATE_NO_WINDOW,
                shell=False,
            )
        except (OSError, subprocess.TimeoutExpired):
            pass
        if process.poll() is None:
            process.kill()
    else:
        deadline = time.monotonic() + 2
        if _signal_process_group(process, signal.SIGTERM, deadline):
            while _signal_process_group(process, 0, deadline):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    _signal_process_group(process, signal.SIGKILL, deadline)
                    break
                time.sleep(min(0.1, remaining))
        if process.poll() is None:
            process.kill()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired as error:
        process.kill()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired as final_error:
            raise RuntimeError("无法在清理期限内回收本次启动的命令进程。") from final_error
        raise RuntimeError("命令进程未在清理宽限期内退出。") from error


def run_process(
    arguments: Sequence[str],
    *,
    cwd: Path,
    env: dict[str, str] | None = None,
    timeout_seconds: float = 1800,
    stream: bool = False,
    shell: bool = False,
) -> subprocess.CompletedProcess[str]:
    """在有界时间内执行单一进程树；超时后先杀树再回收管道和根进程。"""
    if timeout_seconds <= 0:
        raise ValueError("命令超时必须为正数。")
    process = subprocess.Popen(
        list(arguments),
        cwd=cwd,
        env=env,
        stdin=subprocess.DEVNULL,
        stdout=None if stream else subprocess.PIPE,
        stderr=None if stream else subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        shell=shell,
        creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
        start_new_session=os.name != "nt",
    )
    try:
        stdout, _ = process.communicate(timeout=timeout_seconds)
    except KeyboardInterrupt:
        try:
            stop_process_tree(process)
            process.communicate(timeout=10)
        except BaseException:
            if process.poll() is None:
                try:
                    process.kill()
                except OSError:
                    pass
                try:
                    process.wait(timeout=5)
                except BaseException:
                    pass
            for stream in (process.stdout, process.stderr):
                if stream is not None and not stream.closed:
                    stream.close()
        raise
    except subprocess.TimeoutExpired as error:
        stop_process_tree(process)
        try:
            stdout, _ = process.communicate(timeout=10)
        except subprocess.TimeoutExpired as pipe_error:
            stop_process_tree(process)
            raise RuntimeError("命令进程结束后输出管道仍未关闭。") from pipe_error
        raise CommandTimeoutError(
            f"命令超时（{timeout_seconds:g} 秒），已回收本次进程树：{arguments[0]}",
            stdout or "",
        ) from error
    return subprocess.CompletedProcess(list(arguments), process.returncode or 0, stdout, None)


def run_checked(
    executable: str,
    arguments: Sequence[str],
    *,
    cwd: Path,
    env: dict[str, str] | None = None,
    stream: bool = False,
    timeout_seconds: float = 1800,
) -> subprocess.CompletedProcess[str]:
    """用参数数组执行工具；仅 Windows 批处理入口经 Python shell 适配且执行有界。"""
    is_batch = windows_batch_command(executable)
    result = run_process(
        command_argv(executable, arguments),
        cwd=cwd,
        env=env,
        timeout_seconds=timeout_seconds,
        stream=stream,
        shell=is_batch,
    )
    if result.returncode != 0:
        raise RuntimeError(
            f"命令执行失败（exit={result.returncode}）：{executable}"
            + (f"\n{result.stdout.strip()}" if result.stdout else "")
        )
    return result
