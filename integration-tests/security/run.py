#!/usr/bin/env python3
# @author kongweiguang
"""独立编译并执行 Java/Go 安全探针；只生成专用 target 下的公开测试产物。"""

from __future__ import annotations

import argparse
import ctypes
import locale
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys


class WindowsJob:
    """用系统 Job 管理本次测试进程树，避免超时后只结束父进程。"""

    def __init__(self, process: subprocess.Popen[bytes]) -> None:
        """直接使用 Popen 持有的进程句柄，避免按 PID 重新查找时误认已复用的进程。"""
        from ctypes import wintypes

        class BasicLimits(ctypes.Structure):
            _fields_ = [
                ("PerProcessUserTimeLimit", ctypes.c_longlong),
                ("PerJobUserTimeLimit", ctypes.c_longlong),
                ("LimitFlags", wintypes.DWORD),
                ("MinimumWorkingSetSize", ctypes.c_size_t),
                ("MaximumWorkingSetSize", ctypes.c_size_t),
                ("ActiveProcessLimit", wintypes.DWORD),
                ("Affinity", ctypes.c_size_t),
                ("PriorityClass", wintypes.DWORD),
                ("SchedulingClass", wintypes.DWORD),
            ]

        class IoCounters(ctypes.Structure):
            _fields_ = [(name, ctypes.c_ulonglong) for name in (
                "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
                "ReadTransferCount", "WriteTransferCount", "OtherTransferCount",
            )]

        class ExtendedLimits(ctypes.Structure):
            _fields_ = [
                ("BasicLimitInformation", BasicLimits), ("IoInfo", IoCounters),
                ("ProcessMemoryLimit", ctypes.c_size_t), ("JobMemoryLimit", ctypes.c_size_t),
                ("PeakProcessMemoryUsed", ctypes.c_size_t), ("PeakJobMemoryUsed", ctypes.c_size_t),
            ]

        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        self.kernel.CreateJobObjectW.restype = wintypes.HANDLE
        self.kernel.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int,
                                                        ctypes.c_void_p, wintypes.DWORD]
        self.kernel.SetInformationJobObject.restype = wintypes.BOOL
        self.kernel.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        self.kernel.AssignProcessToJobObject.restype = wintypes.BOOL
        self.kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self.kernel.CloseHandle.restype = wintypes.BOOL
        self.handle = self.kernel.CreateJobObjectW(None, None)
        if not self.handle:
            raise ctypes.WinError(ctypes.get_last_error())
        try:
            limits = ExtendedLimits()
            limits.BasicLimitInformation.LimitFlags = 0x2000  # 最后一个 Job 句柄关闭时终止关联进程。
            if not self.kernel.SetInformationJobObject(self.handle, 9, ctypes.byref(limits),
                                                        ctypes.sizeof(limits)):
                raise ctypes.WinError(ctypes.get_last_error())
            # CPython 在 Windows 上保留原始进程句柄；这里只启动可信的工具与测试程序。
            if not self.kernel.AssignProcessToJobObject(self.handle, int(process._handle)):
                if process.poll() is None:
                    raise ctypes.WinError(ctypes.get_last_error())
        except BaseException:
            self.close()
            raise

    def close(self) -> None:
        """幂等释放自有 Job，清理范围不会扩展到同名或无关进程。"""
        if self.handle:
            handle, self.handle = self.handle, None
            if not self.kernel.CloseHandle(handle):
                raise ctypes.WinError(ctypes.get_last_error())


def stop_tree(process: subprocess.Popen[bytes], job: WindowsJob | None) -> None:
    """回收本次命令及其进程组；WSL 内部另外依靠测试期限，不终止整个发行版。"""
    if job is not None:
        job.close()
    elif os.name != "nt":
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    elif process.poll() is None:
        # Job 创建失败时只回收刚创建的直属进程，不按名称搜索系统进程。
        process.kill()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired as error:
        raise RuntimeError("测试子进程未在清理宽限期内结束") from error


def run_checked(executable: str, arguments: list[str], cwd: Path, timeout: int,
                capture: bool = False, additions: dict[str, str] | None = None,
                stdout_only: bool = False) -> str:
    """直接传 argv 并同时排空输出；失败立即上抛，禁止后续成功掩盖失败或自动下载工具链。"""
    environment = os.environ.copy()
    environment["GOTOOLCHAIN"] = "local"
    if additions:
        environment.update(additions)
    flags = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    process = subprocess.Popen([executable, *arguments], cwd=cwd, env=environment,
                               stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               creationflags=flags, start_new_session=os.name != "nt")
    job = None
    try:
        if os.name == "nt":
            job = WindowsJob(process)
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(f"{Path(executable).name} 超过 {timeout} 秒命令期限") from error
        standard_output, diagnostic = decode_output(stdout), decode_output(stderr)
        output = standard_output + diagnostic
        if output and (not capture or process.returncode != 0):
            print(output.rstrip(), flush=True)
        elif stdout_only and diagnostic:
            print(diagnostic.rstrip(), file=sys.stderr, flush=True)
        if process.returncode != 0:
            raise RuntimeError(f"{Path(executable).name} 失败，退出码：{process.returncode}")
        # 读取架构或路径时仅消费 stdout，启动警告不能参与机器结果解析。
        return standard_output if stdout_only else output
    finally:
        stop_tree(process, job)
        # 关闭 Job 或进程组后，继承输出管道的后代也应退出，避免读取线程无限等待。
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError("测试进程树结束后输出管道仍未关闭") from error


def decode_output(value: bytes) -> str:
    """兼容 Windows 自带工具的 UTF-16 和旧 JDK 代码页，其余优先按 UTF-8 读取。"""
    if value.startswith((b"\xff\xfe", b"\xfe\xff")):
        return value.decode("utf-16", errors="replace")
    if len(value) % 2 == 0 and value[1::2].count(0) >= max(2, len(value) // 8):
        return value.decode("utf-16-le", errors="replace")
    try:
        return value.decode("utf-8")
    except UnicodeDecodeError:
        return value.decode(locale.getpreferredencoding(False), errors="replace")


def required_tool(name: str) -> str:
    """生成测试文件前确认真实可执行文件存在，不安装软件，也不使用 shell 别名。"""
    executable = shutil.which(name)
    if not executable:
        raise RuntimeError(f"PATH 中缺少 {name}，请先单独安装或配置该工具")
    return executable


def timeout_value(value: str) -> int:
    """限制外部命令期限，避免误填零值或过大时长让 CI 长期挂起。"""
    try:
        timeout = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("命令期限必须是整数秒") from error
    if not 1 <= timeout <= 1800:
        raise argparse.ArgumentTypeError("命令期限必须在 1～1800 秒之间")
    return timeout


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    """通过显式选项启用 Java 8 和 WSL 实测，不能把编译成功写成运行通过。"""
    parser = argparse.ArgumentParser(description=__doc__, add_help=False)
    parser.add_argument("-h", "--help", action="help", help="显示帮助并退出")
    parser.add_argument("--java8-home", type=Path, help="额外实测的 Java 8 根目录；默认只验证 --release 8 编译")
    parser.add_argument("--linux-wsl", action="store_true", help="仅 Windows：在已安装的 WSL 中运行 Linux 进程树用例")
    parser.add_argument("--wsl-distribution", default="Ubuntu-22.04", help="WSL 发行版名称，默认 Ubuntu-22.04")
    parser.add_argument("--command-timeout-seconds", type=timeout_value, default=180,
                        help="每条外部命令的期限，默认 180 秒，范围 1～1800")
    return parser.parse_args(argv)


def preflight(args: argparse.Namespace, root: Path) -> dict[str, str]:
    """先只读核对工具版本与目标环境，通过后才创建 target；不修改全局 PATH。"""
    if args.linux_wsl and os.name != "nt":
        raise RuntimeError("--linux-wsl 仅适用于 Windows；Linux 原生 go test 会自动运行进程树用例")
    tools = {name: required_tool(name) for name in ("java", "javac", "go")}
    versions = {}
    for name, command in (("java", ["-version"]), ("javac", ["-version"]), ("go", ["version"])):
        versions[name] = run_checked(tools[name], command, root, args.command_timeout_seconds, capture=True).strip()
    java_match = re.search(r'version "(\d+)', versions["java"])
    javac_match = re.search(r"\bjavac (\d+)", versions["javac"])
    go_match = re.search(r"\bgo(\d+)\.(\d+)", versions["go"])
    if not java_match or int(java_match[1]) < 21:
        raise RuntimeError("PATH 中的 Java 必须为 21 或以上；额外 Java 8 请用 --java8-home")
    if not javac_match or int(javac_match[1]) < 21:
        raise RuntimeError("PATH 中的 javac 必须为 21 或以上")
    if not go_match or (int(go_match[1]), int(go_match[2])) < (1, 24):
        raise RuntimeError("Go 必须为 1.24 或以上；本脚本不会下载工具链")
    if args.java8_home:
        java8 = args.java8_home.resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
        if not java8.is_file():
            raise RuntimeError("--java8-home 中没有当前平台的 bin/java 可执行文件")
        version = run_checked(str(java8), ["-version"], root, args.command_timeout_seconds, capture=True)
        if not re.search(r'version "1\.8\.', version):
            raise RuntimeError("--java8-home 必须指向真实 Java 8，不能用更高版本替代")
        tools["java8"] = str(java8)
    if args.linux_wsl:
        tools["wsl"] = required_tool("wsl")
        machine = run_checked(tools["wsl"], ["-d", args.wsl_distribution, "--exec", "uname", "-m"],
                              root, args.command_timeout_seconds, capture=True, stdout_only=True).strip()
        architecture = {"x86_64": "amd64", "aarch64": "arm64"}.get(machine)
        if architecture is None:
            raise RuntimeError("WSL 发行版仅支持 x86_64 或 aarch64")
        tools["wsl_architecture"] = architecture
    print(f"预检通过：{versions['javac']}；{versions['go']}；每条命令限时 {args.command_timeout_seconds} 秒", flush=True)
    return tools


def run_probes(args: argparse.Namespace) -> None:
    """保持原有跨语言断言，只调整目录归类和运行器；产物仅写当前安全探针的 target。"""
    directory = Path(__file__).resolve().parent
    root = directory.parents[1]
    tools = preflight(args, root)
    run_checked(sys.executable, ["-B", "-m", "unittest", "discover", "-s", str(directory), "-p", "test_run.py"],
                root, args.command_timeout_seconds)
    source_roots = [root / "xjar-plus-format/src/main/java",
                    root / "xjar-plus-runtime/src/main/java/io/github/kongweiguang/xjar/plus/runtime/common"]
    sources = []
    for source_root in source_roots:
        group = sorted(source_root.rglob("*.java"))
        if not group:
            raise RuntimeError(f"未找到探针依赖源码：{source_root.relative_to(root)}")
        sources.extend(str(source.relative_to(root)) for source in group)
    for name in ("FormatSecurityProbe.java", "RuntimeSecurityProbe.java", "RuntimeBrokerProbe.java"):
        source = directory / name
        if not source.is_file():
            raise RuntimeError(f"缺少安全探针源码：{name}")
        sources.append(str(source.relative_to(root)))
    classes = directory / "target/classes"
    classes.mkdir(parents=True, exist_ok=True)
    run_checked(tools["javac"], ["--release", "8", "-encoding", "UTF-8", "-d", str(classes), *sources],
                root, args.command_timeout_seconds)
    probes = ["io.github.kongweiguang.xjar.plus.format.FormatSecurityProbe",
              "io.github.kongweiguang.xjar.plus.runtime.common.RuntimeSecurityProbe"]
    for java in [tools["java"], *([tools["java8"]] if "java8" in tools else [])]:
        for probe in probes:
            run_checked(java, ["-cp", str(classes), probe, str(root)], root, args.command_timeout_seconds)
    if "java8" not in tools:
        print("未要求真实 Java 8 执行；--release 8 仅证明编译兼容。", flush=True)
    go_timeout = f"{max(1, args.command_timeout_seconds - 15)}s"
    run_checked(tools["go"], ["test", "-count=1", "-timeout", go_timeout, "-v", "./..."],
                directory, args.command_timeout_seconds, additions={"XJP_SECURITY_JAVA8": tools.get("java8", "")})
    if args.linux_wsl:
        run_checked(tools["go"], ["test", "-c", "-o", "target/security-linux.test", "."],
                    directory, args.command_timeout_seconds,
                    additions={"GOOS": "linux", "GOARCH": tools["wsl_architecture"]})
        mapped = run_checked(tools["wsl"], ["-d", args.wsl_distribution, "--exec", "wslpath", "-a", directory.as_posix()],
                             directory, args.command_timeout_seconds, capture=True, stdout_only=True).strip()
        if not mapped:
            raise RuntimeError("无法确定安全探针目录在 WSL 中的映射路径")
        run_checked(tools["wsl"], ["-d", args.wsl_distribution, "--cd", mapped, "--exec",
                                   "./target/security-linux.test", "-test.v", f"-test.timeout={go_timeout}",
                                   "-test.run=^TestUnix"], directory, args.command_timeout_seconds)


def main(argv: list[str] | None = None) -> int:
    """把取消与失败变成明确退出码，只有全部探针完成后才报告成功。"""
    # 统一终端与重定向日志的编码，避免 Windows 管道按本地代码页输出中文。
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="backslashreplace", newline="\n")
    args = parse_args(argv)
    try:
        run_probes(args)
    except KeyboardInterrupt:
        print("安全探针已取消；本次工具进程已请求清理。", file=sys.stderr)
        return 130
    except (OSError, RuntimeError) as error:
        print(f"安全探针失败：{error}", file=sys.stderr)
        return 1
    print("本次请求的安全探针全部完成。", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
