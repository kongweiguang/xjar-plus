# @author kongweiguang
"""在本机真实运行 Go 启动器，验证授权拒绝、到期停机和续期重启闭环。"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

from _delivery_common import stop_process_tree


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CLI = REPOSITORY_ROOT / "xjar-plus-app" / "target" / "xjar-plus-2.0.0.jar"
DEFAULT_FIXTURE = (
    REPOSITORY_ROOT / "integration-tests" / "fixtures" / "plain-java8" / "target"
    / "plain-java8-fixture.jar"
)
STATUS_LOG_PATTERN = re.compile(
    r"license status service listening at http://127\.0\.0\.1:(\d+)/xjp/license/status"
)
APP_LOG_PATTERN = re.compile(r"HTTP_READY=(\d+)")
DIAGNOSTIC_LOG_TAIL_BYTES = 4 * 1024
DIAGNOSTIC_STATUS_FIELDS = (
    "available",
    "valid",
    "appRunning",
    "serverTime",
    "validEndDate",
    "remainingMs",
)


class VerificationError(RuntimeError):
    """验收断言失败时保留当前边界，不把连接成功误判成业务就绪。"""


@dataclass
class LauncherProcess:
    """记录单次自有启动器进程及其私有日志，便于失败时只回收本次进程树。"""

    process: subprocess.Popen[bytes]
    working_directory: Path
    log_path: Path
    status_port: int


def parse_target(value: str) -> tuple[str, str]:
    """限制到当前承诺的六个平台组合，防止把交叉编译误当成可运行验收。"""
    supported = {
        f"{goos}-{goarch}"
        for goos in ("windows", "linux", "darwin")
        for goarch in ("amd64", "arm64")
    }
    if value not in supported:
        raise argparse.ArgumentTypeError(f"不支持的目标平台：{value}")
    return tuple(value.split("-", maxsplit=1))  # type: ignore[return-value]


def native_target() -> str:
    """只返回脚本能实际执行的当前主机三元组，拒绝静默交叉运行。"""
    os_name = {"Windows": "windows", "Linux": "linux", "Darwin": "darwin"}.get(platform.system())
    architecture = platform.machine().lower()
    arch_name = {"amd64": "amd64", "x86_64": "amd64", "arm64": "arm64", "aarch64": "arm64"}.get(
        architecture
    )
    if os_name is None or arch_name is None:
        raise VerificationError(f"当前系统或架构暂不支持真实启动器验收：{platform.system()} {architecture}")
    return f"{os_name}-{arch_name}"


def resolve_executable(value: str, executable_name: str) -> Path:
    """支持 JDK 根目录、可执行文件路径和 PATH 命令名，减少调用方路径歧义。"""
    candidate = Path(value).expanduser()
    if candidate.is_dir():
        name = executable_name + (".exe" if os.name == "nt" else "")
        candidate = candidate / "bin" / name
    if candidate.is_file():
        return candidate.resolve()
    resolved = shutil.which(value)
    if resolved is None:
        raise VerificationError(f"找不到可执行文件：{value}")
    return Path(resolved).resolve()


def run_checked(command: list[str], timeout: int, description: str) -> subprocess.CompletedProcess[str]:
    """保留 stdout/stderr 分流供 CLI 解析，并在超时或取消时有界回收整棵子树。"""
    if not command:
        raise VerificationError(f"{description} 未完成：命令为空")
    creation_flags = 0
    if os.name == "nt":
        creation_flags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    try:
        process = subprocess.Popen(
            command,
            cwd=REPOSITORY_ROOT,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            start_new_session=(os.name != "nt"),
            creationflags=creation_flags,
            shell=False,
        )
    except OSError as error:
        raise VerificationError(f"{description} 未完成：{error}") from error
    try:
        stdout_bytes, stderr_bytes = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired as error:
        try:
            terminate_owned_tree(process)
            stdout_bytes, stderr_bytes = process.communicate(timeout=10)
        except subprocess.TimeoutExpired as pipe_error:
            try:
                terminate_owned_tree(process)
            finally:
                close_process_pipes(process)
            raise VerificationError(f"{description} 超时后输出管道仍未关闭") from pipe_error
        except Exception:
            close_process_pipes(process)
            raise
        raise VerificationError(f"{description} 超时（{timeout}s）") from error
    except KeyboardInterrupt:
        try:
            terminate_owned_tree(process)
            process.communicate(timeout=10)
        except Exception:
            if process.poll() is None:
                process.kill()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    pass
            close_process_pipes(process)
        raise
    result = subprocess.CompletedProcess(
        command,
        process.returncode,
        stdout_bytes.decode("utf-8", errors="replace"),
        stderr_bytes.decode("utf-8", errors="replace"),
    )
    if result.returncode != 0:
        output = (result.stdout + result.stderr).strip()
        raise VerificationError(f"{description} 失败（exit={result.returncode}）：{output}")
    return result


def close_process_pipes(process: subprocess.Popen[bytes]) -> None:
    """在有界回收仍无法关闭继承管道时释放本进程句柄，避免再进入无期限读取。"""
    for pipe in (process.stdout, process.stderr):
        if pipe is not None:
            pipe.close()


def verify_toolchains(java: Path, go: Path) -> tuple[int, str]:
    """在构建前确认 CLI 的 Java 21 下限和启动器 Go 1.24 下限。"""
    java_result = run_checked([str(java), "-version"], 15, "Java 版本检查")
    java_output = java_result.stdout + java_result.stderr
    java_match = re.search(r'version "(\d+)\.', java_output)
    if java_match is None or int(java_match.group(1)) < 21:
        raise VerificationError(f"CLI 至少需要 Java 21；版本输出：{java_output.strip()}")
    go_result = run_checked([str(go), "version"], 15, "Go 版本检查")
    go_match = re.search(r"\bgo version go(\d+)\.(\d+)(?:\.(\d+))?\b", go_result.stdout + go_result.stderr)
    if go_match is None or (int(go_match.group(1)), int(go_match.group(2))) < (1, 24):
        raise VerificationError(f"Go 启动器需要 Go 1.24 或更新版本；版本输出：{go_result.stdout.strip()}")
    return int(java_match.group(1)), go_match.group(0)


def validate_run_root(target_root: Path, run_root: Path) -> Path:
    """只允许清理 integration-tests/target 下本脚本命名的直接子目录。"""
    expected_parent = target_root.resolve()
    candidate = run_root.absolute()
    resolved_parent = candidate.parent.resolve()
    if resolved_parent != expected_parent or not candidate.name.startswith("launcher-verification-"):
        raise VerificationError(f"拒绝在验收临时目录之外操作：{candidate}")
    if candidate.is_symlink():
        raise VerificationError(f"拒绝将验收临时目录符号链接作为清理目标：{candidate}")
    resolved = candidate.resolve()
    if resolved.parent != expected_parent:
        raise VerificationError(f"验收临时目录解析后越出限定根目录：{resolved}")
    return resolved


def create_run_root() -> tuple[str, Path]:
    """用随机 RunId 创建专属验收目录，避免复用或清除其他测试遗留物。"""
    run_id = uuid.uuid4().hex
    target_root = REPOSITORY_ROOT / "integration-tests" / "target"
    target_root.mkdir(parents=True, exist_ok=True)
    run_root = target_root / f"launcher-verification-{run_id}"
    run_root.mkdir(mode=0o700)
    return run_id, validate_run_root(target_root, run_root)


def ensure_file(path: Path, label: str) -> Path:
    """在产生临时凭据前检查必需输入，避免把输入错误留成半套测试产物。"""
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise VerificationError(f"找不到{label}：{resolved}")
    return resolved


def invoke_cli(java: Path, cli_jar: Path, arguments: list[str], timeout: int) -> str:
    """以 argv 调用实际 CLI，stdout 保留机器结果而错误只呈现 UTF-8 诊断。"""
    command = [str(java), "-Dfile.encoding=UTF-8", "-jar", str(cli_jar), *arguments]
    result = run_checked(command, timeout, f"CLI {' '.join(arguments[:2])}")
    return result.stdout.strip()


def parse_build_result(output: str, base_directory: Path) -> Path:
    """按 CLI 的 BuildResult JSON 定位实际 launcher，不从 appId 猜产物文件名。"""
    try:
        result = json.loads(output)
    except json.JSONDecodeError as error:
        raise VerificationError(f"CLI 未返回可解析的产物 JSON：{output}") from error
    value = result.get("output") if isinstance(result, dict) else None
    if not isinstance(value, str) or not value.strip():
        raise VerificationError(f"CLI 产物 JSON 缺少 output 字段：{output}")
    path = Path(value)
    if not path.is_absolute():
        path = base_directory / path
    path = path.resolve()
    if not path.is_file() or path.stat().st_size == 0:
        raise VerificationError(f"package 未生成非空 launcher：{path}")
    return path


def instant_text(value: datetime) -> str:
    """把签发时刻统一到协议的整秒 UTC，避免本机时区影响有效区间。"""
    return value.astimezone(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def issue_license(
    java: Path,
    cli_jar: Path,
    issuer_file: Path,
    output: Path,
    not_before: datetime,
    not_after: datetime,
    timeout: int,
) -> Path:
    """用同一发行凭据签发隔离测试许可证，秘密材料始终只留在临时目录。"""
    invoke_cli(
        java,
        cli_jar,
        [
            "license", "issue", "--issuer-file", str(issuer_file), "--output", str(output),
            "--not-before", instant_text(not_before), "--not-after", instant_text(not_after),
        ],
        timeout,
    )
    if not output.is_file() or output.stat().st_size == 0:
        raise VerificationError(f"许可证签发未生成文件：{output}")
    return output


def read_status(port: int, timeout: float = 2.0) -> dict[str, object]:
    """读取只读许可证快照并校验 JSON 根类型，HTTP 200 本身不代表授权有效。"""
    request = urllib.request.Request(f"http://127.0.0.1:{port}/xjp/license/status", method="GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise VerificationError(f"license/status HTTP 状态异常：{response.status}")
        value = json.loads(response.read().decode("utf-8"))
    if not isinstance(value, dict):
        raise VerificationError("license/status 响应不是 JSON 对象")
    for field in ("available", "valid", "appRunning"):
        if not isinstance(value.get(field), bool):
            raise VerificationError(f"license/status 缺少布尔字段：{field}")
    return value


def check_health(port: int) -> None:
    """把 healthz 限定为状态服务可达断言，不将其当作业务 readiness。"""
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/xjp/healthz", timeout=3) as response:
        body = response.read().decode("utf-8").strip()
        if response.status != 200 or body != "ok":
            raise VerificationError(f"healthz 响应异常：HTTP {response.status} {body!r}")


def request_fixture(port: int, path: str, timeout: float = 3.0) -> str:
    """通过 fixture 自身 HTTP 响应验证受保护 JAR 的真实类、SPI 与资源加载。"""
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=timeout) as response:
        if response.status != 200:
            raise VerificationError(f"fixture {path} HTTP 状态异常：{response.status}")
        return response.read().decode("utf-8")


def read_log(path: Path) -> str:
    """用宽容 UTF-8 解码读取自有进程日志，避免宿主代码页掩盖关键状态行。"""
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def read_diagnostic_log_tail(path: Path) -> str:
    """只读取固定验收 fixture 的日志末尾并白名单化事件，避免将应用任意输出或密钥写入诊断。"""
    try:
        with path.open("rb") as log_file:
            log_file.seek(0, 2)
            size = log_file.tell()
            offset = max(0, size - DIAGNOSTIC_LOG_TAIL_BYTES)
            log_file.seek(offset)
            data = log_file.read(DIAGNOSTIC_LOG_TAIL_BYTES)
    except OSError:
        return "（launcher 日志不可读）"

    text = data.decode("utf-8", errors="replace")
    if offset > 0:
        text = text.partition("\n")[2]

    safe_lines: list[str] = []
    for line in text.splitlines():
        normalized = re.sub(r"^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2} ", "", line)
        if normalized in {
            "xjar-plus: license is no longer valid; stopping application",
            "xjar-plus: data-key broker failed before delivery",
        }:
            safe_lines.append(normalized)
        elif normalized.startswith("xjar-plus: stop child after signal:"):
            safe_lines.append("xjar-plus: stop child after signal: [详情省略]")
        elif normalized.startswith("xjar-plus: stop child after data-key broker failure:"):
            safe_lines.append("xjar-plus: stop child after data-key broker failure: [详情省略]")
        elif normalized.startswith("xjar-plus: stop expired application:"):
            safe_lines.append("xjar-plus: stop expired application: [详情省略]")
        elif normalized.startswith("xjar-plus: clean private runtime directory"):
            safe_lines.append("xjar-plus: clean private runtime directory: [详情省略]")
        elif re.fullmatch(r"HTTP_READY=\d+", normalized):
            safe_lines.append(normalized)
        elif re.fullmatch(
            r"xjar-plus: license status service listening at http://127\.0\.0\.1:\d+/xjp/license/status",
            normalized,
        ):
            safe_lines.append("xjar-plus: license status service listening at loopback")
    if not safe_lines:
        return "（日志尾部没有白名单诊断事件）"
    return "\n".join(safe_lines[-32:])


def diagnostic_status_fields(status: dict[str, object]) -> dict[str, object]:
    """按状态 API schema 输出有限字段及严格类型，禁止未知字段混入异常诊断。"""
    result: dict[str, object] = {}
    expected_types = {
        "available": bool,
        "valid": bool,
        "appRunning": bool,
        "serverTime": str,
        "validEndDate": str,
        "remainingMs": int,
    }
    for field in DIAGNOSTIC_STATUS_FIELDS:
        value = status.get(field)
        expected_type = expected_types[field]
        if type(value) is expected_type:
            result[field] = value
    return result


def terminate_owned_tree(process: subprocess.Popen[bytes]) -> None:
    """即使根进程已退出也回收其隔离进程组，复用共享的有界 TERM/KILL 策略。"""
    try:
        stop_process_tree(process)
    except (OSError, RuntimeError, subprocess.SubprocessError) as error:
        raise VerificationError(f"未能回收本次 launcher 进程树，根 PID={process.pid}：{error}") from error


def launcher_environment(java: Path, temp_directory: Path) -> dict[str, str]:
    """让外部 Java 运行时明确选中验收传入的 JDK，避免继承到另一套 JAVA_HOME。"""
    environment = os.environ.copy()
    java_home = java.parent.parent
    environment["JAVA_HOME"] = str(java_home)
    environment["PATH"] = str(java.parent) + os.pathsep + environment.get("PATH", "")
    environment["XJAR_PLUS_STATUS_PORT"] = "0"
    for key in ("TMPDIR", "TEMP", "TMP"):
        environment[key] = str(temp_directory)
    return environment


def start_launcher(
    binary: Path,
    working_directory: Path,
    java: Path,
    temp_directory: Path,
    log_path: Path,
    active_launchers: list[LauncherProcess],
) -> LauncherProcess:
    """启动时先登记自有进程，再等待状态端口，以便启动失败或 Ctrl+C 仍能清理。"""
    environment = launcher_environment(java, temp_directory)
    creation_flags = 0
    if os.name == "nt":
        creation_flags = subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW
    with log_path.open("wb") as log_file:
        process = subprocess.Popen(
            [str(binary)],
            cwd=working_directory,
            env=environment,
            stdin=subprocess.DEVNULL,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            start_new_session=(os.name != "nt"),
            creationflags=creation_flags,
            shell=False,
        )
    launcher = LauncherProcess(process, working_directory, log_path, 0)
    active_launchers.append(launcher)
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise VerificationError(
                f"launcher 提前退出（exit={process.returncode}）：{read_log(log_path).strip()}"
            )
        match = STATUS_LOG_PATTERN.search(read_log(log_path))
        if match is not None:
            launcher.status_port = int(match.group(1))
            return launcher
        time.sleep(0.1)
    terminate_owned_tree(process)
    raise VerificationError(f"launcher 未在 30 秒内报告状态端口：{read_log(log_path).strip()}")


def wait_for_status(
    launcher: LauncherProcess,
    predicate,
    timeout: float,
    description: str,
) -> dict[str, object]:
    """等待明确的许可证与业务进程状态，同时监视 launcher 是否意外退出。"""
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        if launcher.process.poll() is not None:
            raise VerificationError(
                f"等待{description}期间 launcher 退出（exit={launcher.process.returncode}）："
                f"{read_log(launcher.log_path).strip()}"
            )
        try:
            check_health(launcher.status_port)
            status = read_status(launcher.status_port)
            if predicate(status):
                return status
            last_error = None
        except (OSError, urllib.error.URLError, TimeoutError, json.JSONDecodeError) as error:
            last_error = error
        time.sleep(0.1)
    detail = f"；最近连接错误：{last_error}" if last_error else ""
    raise VerificationError(f"状态未在 {timeout:g}s 内达到{description}{detail}")


def wait_for_log_port(launcher: LauncherProcess, pattern: re.Pattern[str], timeout: float) -> int:
    """从 fixture 自己的 ready 行取得系统分配端口，不猜测第二个服务的端口。"""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        match = pattern.search(read_log(launcher.log_path))
        if match is not None:
            return int(match.group(1))
        if launcher.process.poll() is not None:
            raise VerificationError(f"业务进程退出前未报告 HTTP ready：{read_log(launcher.log_path).strip()}")
        time.sleep(0.1)
    raise VerificationError(f"业务 fixture 未在 {timeout:g}s 内就绪：{read_log(launcher.log_path).strip()}")


def make_bundle(binary: Path, license_file: Path | None, destination: Path) -> Path:
    """每个许可证场景复制独立 launcher 目录，避免并行状态或许可证互相污染。"""
    destination.mkdir(mode=0o700)
    target = destination / binary.name
    shutil.copy2(binary, target)
    if license_file is not None:
        shutil.copyfile(license_file, destination / "key.x")
    return target


def assert_rejected(
    binary: Path,
    working_directory: Path,
    java: Path,
    temp_directory: Path,
    log_path: Path,
    expect_available: bool,
    label: str,
    active_launchers: list[LauncherProcess],
) -> None:
    """验证无效授权仅保留状态服务，并登记自有进程供外层失败清理兜底。"""
    launcher = start_launcher(binary, working_directory, java, temp_directory, log_path, active_launchers)
    try:
        status = wait_for_status(
            launcher,
            lambda value: value["valid"] is False and value["appRunning"] is False,
            20,
            f"{label} fail-closed",
        )
        if status["available"] is not expect_available:
            raise VerificationError(f"{label} available 状态不符：{json.dumps(status, ensure_ascii=False)}")
        if APP_LOG_PATTERN.search(read_log(log_path)):
            raise VerificationError(f"{label} 失败：无效授权场景仍启动了业务 fixture")
        deadline = time.monotonic() + 0.75
        while time.monotonic() < deadline:
            check_health(launcher.status_port)
            current = read_status(launcher.status_port)
            if current["valid"] or current["appRunning"] or launcher.process.poll() is not None:
                raise VerificationError(f"{label} 未持续 fail-closed：{json.dumps(current, ensure_ascii=False)}")
            time.sleep(0.1)
        if list(temp_directory.glob("xjar-plus-private-*")):
            raise VerificationError(f"{label} 拒绝业务后仍留下私有运行目录")
    finally:
        terminate_owned_tree(launcher.process)
        active_launchers.remove(launcher)


def assert_health_and_resources(launcher: LauncherProcess, app_port: int) -> None:
    """要求授权状态与真实 HTTP/SPI/资源结果同时成立，避免仅凭进程存在误报。"""
    status = read_status(launcher.status_port)
    if not status["available"] or not status["valid"] or not status["appRunning"]:
        raise VerificationError(f"有效授权状态字段不一致：{json.dumps(status, ensure_ascii=False)}")
    probe = request_fixture(app_port, "/probe")
    if "plain-provider" not in probe or "integration resource" not in probe:
        raise VerificationError(f"受保护 JAR 的 HTTP/SPI/资源回归失败：{probe!r}")
    asset = request_fixture(app_port, "/asset")
    if "integration resource" not in asset:
        raise VerificationError(f"受保护 JAR 的静态资源回归失败：{asset!r}")


def wait_for_expiry(
    launcher: LauncherProcess,
    app_port: int,
    not_after: datetime,
    temp_directory: Path,
) -> float:
    """按绝对截止点监督业务进程；优雅退出可先关闭 HTTP，但状态与清理契约必须闭环。"""
    absolute_end = not_after.timestamp()
    hard_deadline = absolute_end + 6.0
    while time.time() <= hard_deadline:
        if launcher.process.poll() is not None:
            raise VerificationError(
                f"到期期间状态服务提前退出（exit={launcher.process.returncode}）："
                f"{read_log(launcher.log_path).strip()}"
            )
        check_health(launcher.status_port)
        status = read_status(launcher.status_port)
        if status["available"] is not True:
            raise VerificationError("运行中的短期许可证在截止前变为不可用")
        if status["valid"] is True and status["appRunning"] is False:
            observed_epoch = time.time()
            observed_utc = datetime.fromtimestamp(observed_epoch, timezone.utc).isoformat(
                timespec="microseconds"
            ).replace("+00:00", "Z")
            not_after_text = not_after.astimezone(timezone.utc).isoformat(
                timespec="seconds"
            ).replace("+00:00", "Z")
            try:
                launcher_exit_code = launcher.process.wait(timeout=0.25)
            except subprocess.TimeoutExpired:
                launcher_exit_code = launcher.process.poll()
            diagnostic = {
                "observedUtc": observed_utc,
                "notAfter": not_after_text,
                "launcherExitCode": launcher_exit_code,
                "status": diagnostic_status_fields(status),
                "launcherLogTail": read_diagnostic_log_tail(launcher.log_path),
            }
            raise VerificationError(
                "许可证仍有效时业务进程已停止；诊断="
                + json.dumps(diagnostic, ensure_ascii=False, sort_keys=True)
            )
        if status["valid"] is False and status["appRunning"] is False:
            observed_at = time.time()
            elapsed = observed_at - absolute_end
            if elapsed < 0:
                raise VerificationError("业务进程在许可证截止前已停止")
            if elapsed > 6.0:
                raise VerificationError(f"业务到期停止超过截止点加 6 秒：{elapsed:.3f}s")

            # appRunning 表示受管进程生命周期；退出钩子可先关闭 HTTP，所以等待清理时只核对状态服务与状态快照。
            cleanup_deadline = time.monotonic() + 4.0
            while list(temp_directory.glob("xjar-plus-private-*")):
                if launcher.process.poll() is not None:
                    raise VerificationError(
                        f"业务停止后状态服务提前退出（exit={launcher.process.returncode}）："
                        f"{read_log(launcher.log_path).strip()}"
                    )
                check_health(launcher.status_port)
                cleanup_status = read_status(launcher.status_port)
                if cleanup_status["available"] is not True:
                    raise VerificationError("等待私有运行目录清理时状态服务变为不可用")
                if cleanup_status["valid"] is not False or cleanup_status["appRunning"] is not False:
                    raise VerificationError("私有运行目录清理期间业务状态意外恢复")
                if time.monotonic() >= cleanup_deadline:
                    raise VerificationError("业务进程已退出，但 Go 启动器未清理私有运行目录")
                time.sleep(0.1)
            if list(temp_directory.glob("xjar-plus-private-*")):
                raise VerificationError("业务进程已退出，但 Go 启动器未清理私有运行目录")

            # 清理等待可能跨过状态轮询周期，因此返回成功前重新证明状态服务仍存活且业务确已停止。
            if launcher.process.poll() is not None:
                raise VerificationError(
                    f"业务停止后状态服务提前退出（exit={launcher.process.returncode}）："
                    f"{read_log(launcher.log_path).strip()}"
                )
            check_health(launcher.status_port)
            final_status = read_status(launcher.status_port)
            if final_status["available"] is not True:
                raise VerificationError("业务停止并清理后状态服务不可用")
            if final_status["valid"] is not False or final_status["appRunning"] is not False:
                raise VerificationError("私有运行目录清理后业务状态不再是已停止")
            try:
                request_fixture(app_port, "/probe", timeout=0.5)
            except urllib.error.HTTPError as error:
                raise VerificationError(
                    f"status 已报告 appRunning=false，但业务 HTTP 仍响应（HTTP {error.code}）"
                ) from error
            except (OSError, urllib.error.URLError, TimeoutError):
                return elapsed
            raise VerificationError("status 已报告 appRunning=false，但受管业务 HTTP 仍可访问")
        time.sleep(0.1)
    raise VerificationError("许可证到期后六秒内未确认业务进程停止；状态服务或业务状态异常")


def atomic_replace(source: Path, destination: Path) -> None:
    """先在同一目录写完整许可证再原子替换，模拟部署续期而不暴露半写文件。"""
    temporary = destination.with_name(f".{destination.name}.{uuid.uuid4().hex}.tmp")
    shutil.copyfile(source, temporary)
    os.replace(temporary, destination)


def remove_run_root(target_root: Path, run_root: Path) -> None:
    """在确认进程已回收后重试清理本次唯一临时树，保留并报告不可清理材料。"""
    root = validate_run_root(target_root, run_root)
    last_error: OSError | None = None
    for attempt in range(5):
        try:
            shutil.rmtree(root)
            return
        except FileNotFoundError:
            return
        except OSError as error:
            last_error = error
            time.sleep(0.1 * (attempt + 1))
    raise VerificationError(f"无法清理本次验收目录，请检查后手动删除：{root}；原因：{last_error}")


def argument_parser() -> argparse.ArgumentParser:
    """为开发者和 CI 提供中文说明、kebab-case 参数与可控短期授权窗口。"""
    parser = argparse.ArgumentParser(
        description="真实打包并运行本机 Go 启动器，验证 fail-closed、到期清理和续期重启。"
    )
    parser.add_argument("--xjar-plus-jar", type=Path, default=DEFAULT_CLI,
                        help="已构建的 CLI JAR；默认使用 xjar-plus-app/target。")
    parser.add_argument("--fixture-jar", type=Path, default=DEFAULT_FIXTURE,
                        help="普通可执行 fixture JAR；默认使用 plain-java8 fixture。")
    parser.add_argument("--java-executable", default=os.environ.get("XJP_JAVA21", "java"), metavar="PATH",
                        help="Java 21+ 可执行文件或 JDK 根目录；默认读 XJP_JAVA21 或 PATH。")
    parser.add_argument("--go-executable", default="go", metavar="PATH",
                        help="Go 1.24+ 编译器路径；默认从 PATH 查找 go。")
    parser.add_argument("--target", type=parse_target, default=None, metavar="OS-ARCH",
                        help="真实执行目标，必须与当前主机相同；默认自动识别本机。")
    parser.add_argument("--jdk-zip", type=Path, default=None, metavar="FILE",
                        help="可选目标平台 JDK ZIP；提供时同时验证嵌入 JDK 的提取和到期清理。")
    parser.add_argument("--license-seconds", type=int, default=30, metavar="SECONDS",
                        help="打包结束后签发的短期授权秒数，范围 20..600；默认 30。")
    parser.add_argument("--timeout-seconds", type=int, default=600, metavar="SECONDS",
                        help="每个 CLI/Go 构建步骤的超时；默认 600 秒。")
    return parser


def run_acceptance(arguments: argparse.Namespace) -> tuple[str, float, int, str, str]:
    """串行完成真实 package、授权失败矩阵、到期停止、状态续存与续期重启。"""
    target = "-".join(arguments.target) if arguments.target else native_target()
    if target != native_target():
        raise VerificationError(f"此脚本会执行产物，目标必须与本机一致：requested={target} native={native_target()}")
    if not 20 <= arguments.license_seconds <= 600:
        raise VerificationError("--license-seconds 必须在 20..600 范围内")
    if arguments.timeout_seconds < 1:
        raise VerificationError("--timeout-seconds 必须为正数")

    cli_jar = ensure_file(arguments.xjar_plus_jar, "可执行 CLI JAR")
    fixture_jar = ensure_file(arguments.fixture_jar, "普通 Java fixture JAR")
    jdk_zip = ensure_file(arguments.jdk_zip, "目标 JDK ZIP") if arguments.jdk_zip else None
    java = resolve_executable(arguments.java_executable, "java")
    go = resolve_executable(arguments.go_executable, "go")
    java_major, go_version = verify_toolchains(java, go)

    run_id, run_root = create_run_root()
    target_root = REPOSITORY_ROOT / "integration-tests" / "target"
    launcher_processes: list[LauncherProcess] = []
    try:
        temp_directory = run_root / "private-temp"
        temp_directory.mkdir(mode=0o700)
        password_file = run_root / "password.txt"
        password_file.write_text(uuid.uuid4().hex + uuid.uuid4().hex + "\n", encoding="utf-8")
        try:
            password_file.chmod(0o600)
        except OSError:
            pass

        app_id = f"verify-{run_id}"
        issuer_file = run_root / f"{app_id}.issuer"
        invoke_cli(
            java,
            cli_jar,
            ["license", "init", "--output", str(issuer_file), "--app-id", app_id],
            arguments.timeout_seconds,
        )
        if not issuer_file.is_file():
            raise VerificationError("license init 未生成发行凭据")

        package_output = run_root / "package-output"
        package_output.mkdir()
        build_started = datetime.now(timezone.utc).replace(microsecond=0)
        package_result = invoke_cli(
            java,
            cli_jar,
            [
                "package", "--input", str(fixture_jar), "--output", str(package_output),
                "--app-id", app_id, "--issuer-file", str(issuer_file), "--target", target,
                "--password-file", str(password_file), "--go-executable", str(go),
                "--not-before", instant_text(build_started - timedelta(minutes=1)),
                "--not-after", instant_text(build_started + timedelta(hours=1)),
                "--app-arg=--serve", "--app-arg=--port=0",
            ] + (["--jdk-zip", str(jdk_zip)] if jdk_zip else []),
            arguments.timeout_seconds,
        )
        launcher_binary = parse_build_result(package_result, package_output)
        original_key = launcher_binary.parent / "key.x"
        if not original_key.is_file():
            raise VerificationError(f"package 未生成同目录 key.x：{original_key}")

        # 先验证缺失、损坏、尚未生效与过期授权，确保诊断服务可用但不会进入业务运行准备。
        missing_directory = run_root / "missing-license"
        missing_binary = make_bundle(launcher_binary, None, missing_directory)
        assert_rejected(missing_binary, missing_directory, java, temp_directory,
                        run_root / "missing-license.log", False, "缺失许可证", launcher_processes)

        invalid_directory = run_root / "invalid-license"
        invalid_binary = make_bundle(launcher_binary, original_key, invalid_directory)
        corrupted_key = invalid_directory / "key.x"
        corrupted_bytes = bytearray(corrupted_key.read_bytes())
        if len(corrupted_bytes) < 16:
            raise VerificationError("测试许可证过短，不能安全构造损坏样本")
        corrupted_bytes[-1] ^= 0xFF
        corrupted_key.write_bytes(corrupted_bytes)
        assert_rejected(invalid_binary, invalid_directory, java, temp_directory,
                        run_root / "invalid-license.log", False, "损坏许可证", launcher_processes)

        now = datetime.now(timezone.utc).replace(microsecond=0)
        future_key = issue_license(
            java, cli_jar, issuer_file, run_root / "future.key.x",
            now + timedelta(seconds=90), now + timedelta(minutes=10), arguments.timeout_seconds,
        )
        future_directory = run_root / "future-license"
        future_binary = make_bundle(launcher_binary, future_key, future_directory)
        assert_rejected(future_binary, future_directory, java, temp_directory,
                        run_root / "future-license.log", True, "尚未生效许可证", launcher_processes)

        expired_key = issue_license(
            java, cli_jar, issuer_file, run_root / "expired.key.x",
            now - timedelta(minutes=2), now - timedelta(minutes=1), arguments.timeout_seconds,
        )
        expired_directory = run_root / "expired-license"
        expired_binary = make_bundle(launcher_binary, expired_key, expired_directory)
        assert_rejected(expired_binary, expired_directory, java, temp_directory,
                        run_root / "expired-license.log", True, "已过期许可证", launcher_processes)

        # 短期授权在昂贵的 Go 编译结束后才签发，避免构建耗时消耗真实运行验收窗口。
        issued_at = datetime.now(timezone.utc).replace(microsecond=0)
        not_after = issued_at + timedelta(seconds=arguments.license_seconds)
        short_key = issue_license(
            java, cli_jar, issuer_file, run_root / "short.key.x",
            issued_at - timedelta(seconds=10), not_after, arguments.timeout_seconds,
        )
        atomic_replace(short_key, original_key)
        private_root = run_root / "private-temp"
        valid = start_launcher(launcher_binary, launcher_binary.parent, java, private_root,
                               run_root / "valid-launcher.log", launcher_processes)
        status = wait_for_status(
            valid,
            lambda value: value["available"] is True and value["valid"] is True
            and value["appRunning"] is True,
            30,
            "有效许可证且业务进程运行",
        )
        app_port = wait_for_log_port(valid, APP_LOG_PATTERN, 30)
        assert_health_and_resources(valid, app_port)
        private_directories = list(private_root.glob("xjar-plus-private-*"))
        if len(private_directories) != 1:
            raise VerificationError(
                f"有效业务运行时应恰有一个私有目录，实际为 {len(private_directories)}；状态："
                f"{json.dumps(status, ensure_ascii=False)}"
            )

        expiry_elapsed = wait_for_expiry(valid, app_port, not_after, private_root)
        expired_status = read_status(valid.status_port)
        if expired_status["available"] is not True or expired_status["valid"] is not False \
                or expired_status["appRunning"] is not False:
            raise VerificationError(f"到期状态字段不一致：{json.dumps(expired_status, ensure_ascii=False)}")
        check_health(valid.status_port)

        # key.x 替换只影响下次启动；现有授权快照不得热更新或复活业务子进程。
        renewal_at = datetime.now(timezone.utc).replace(microsecond=0)
        renewal_end = renewal_at + timedelta(hours=1)
        renewal_key = issue_license(
            java, cli_jar, issuer_file, run_root / "renewal.key.x",
            renewal_at - timedelta(minutes=1), renewal_end, arguments.timeout_seconds,
        )
        atomic_replace(renewal_key, original_key)
        hold_until = time.monotonic() + 1.0
        while time.monotonic() < hold_until:
            if valid.process.poll() is not None:
                raise VerificationError("替换 key.x 后旧 launcher 意外退出")
            check_health(valid.status_port)
            current = read_status(valid.status_port)
            if current["valid"] or current["appRunning"]:
                raise VerificationError(
                    f"运行中的 launcher 热加载了续期许可证：{json.dumps(current, ensure_ascii=False)}"
                )
            time.sleep(0.1)

        terminate_owned_tree(valid.process)
        restarted = start_launcher(launcher_binary, launcher_binary.parent, java, private_root,
                                   run_root / "renewed-launcher.log", launcher_processes)
        renewed_status = wait_for_status(
            restarted,
            lambda value: value["available"] is True and value["valid"] is True
            and value["appRunning"] is True,
            30,
            "续期许可证重启后生效",
        )
        renewed_port = wait_for_log_port(restarted, APP_LOG_PATTERN, 30)
        assert_health_and_resources(restarted, renewed_port)
        return run_id, expiry_elapsed, java_major, go_version, target
    finally:
        cleanup_complete = True
        for launcher in launcher_processes:
            try:
                terminate_owned_tree(launcher.process)
            except (VerificationError, OSError, subprocess.SubprocessError) as error:
                cleanup_complete = False
                print(f"清理自有 launcher 失败：{error}", file=sys.stderr)
        if cleanup_complete:
            remove_run_root(target_root, run_root)
        else:
            raise VerificationError(f"保留本次目录以避免删除仍被进程使用的数据：{run_root}")


def main(argv: list[str] | None = None) -> int:
    """将验收失败作为非零退出，保留失败诊断同时始终回收本次自有产物。"""
    parser = argument_parser()
    arguments = parser.parse_args(argv)
    try:
        run_id, expiry_elapsed, java_major, go_version, target = run_acceptance(arguments)
    except (
        VerificationError,
        OSError,
        subprocess.SubprocessError,
        urllib.error.URLError,
        ValueError,
    ) as error:
        print(f"启动器验收失败：{error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("启动器验收已取消；本次已登记的进程树会在退出前回收。", file=sys.stderr)
        return 130
    print(
        f"启动器真实验收通过，RunId={run_id}，目标={target}，Java={java_major}，"
        f"{go_version}，到期停止耗时={expiry_elapsed:.3f}s（相对 notAfter）。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
