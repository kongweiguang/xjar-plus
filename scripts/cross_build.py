# @author kongweiguang
"""以可审计的 argv 和目标环境构建 Go 启动器交叉产物。"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import re
import shutil
import sys

from _delivery_common import run_process


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
LAUNCHER_ROOT = REPOSITORY_ROOT / "launcher"
SUPPORTED_TARGETS = {
    f"{goos}-{goarch}"
    for goos in ("windows", "linux", "darwin")
    for goarch in ("amd64", "arm64")
}


class CrossBuildError(RuntimeError):
    """交叉构建失败时保留可读原因，但不暴露无关环境变量。"""


def parse_target(value: str) -> tuple[str, str]:
    """严格解析发布支持的目标组合，防止误将未知平台标记为已支持。"""
    if value not in SUPPORTED_TARGETS:
        choices = ", ".join(sorted(SUPPORTED_TARGETS))
        raise argparse.ArgumentTypeError(f"不支持的目标 {value!r}；可选值：{choices}")
    return tuple(value.split("-", maxsplit=1))  # type: ignore[return-value]


def resolve_executable(value: str) -> str:
    """将 Go 编译器解析为单一可执行路径，避免依赖调用方当前目录。"""
    candidate = Path(value).expanduser()
    if candidate.is_file():
        return str(candidate.resolve())
    resolved = shutil.which(value)
    if resolved is None:
        raise CrossBuildError(f"找不到 Go 编译器：{value}")
    return str(Path(resolved).resolve())


def require_go_124(executable: str) -> str:
    """要求启动器支持的 Go 1.24 工具链，并返回其可诊断版本行。"""
    try:
        result = run_process(
            [executable, "version"], cwd=LAUNCHER_ROOT, timeout_seconds=15
        )
    except (OSError, RuntimeError) as error:
        raise CrossBuildError(f"无法读取 Go 编译器版本：{error}") from error
    output = (result.stdout or "").strip()
    match = re.search(r"\bgo version go(\d+)\.(\d+)(?:\.(\d+))?\b", output)
    if result.returncode != 0 or match is None:
        raise CrossBuildError(f"Go 编译器版本检查失败：{output or result.returncode}")
    version = (int(match.group(1)), int(match.group(2)))
    if version < (1, 24):
        raise CrossBuildError(f"需要 Go 1.24 或更新版本；当前为 {match.group(0)}")
    return match.group(0)


def default_output(target: str) -> Path:
    """把省略输出路径的产物放在 launcher/target，避免散落到源码目录。"""
    suffix = ".exe" if target.startswith("windows-") else ""
    return LAUNCHER_ROOT / "target" / f"xjp-{target}{suffix}"


def build(target: str, go_executable: str = "go", output: Path | None = None) -> Path:
    """在目标私有路径运行 Go 编译，失败时只删除本次生成的半成品。"""
    goos, goarch = parse_target(target)
    destination = (output or default_output(target)).expanduser().absolute()
    if os.path.lexists(destination):
        raise CrossBuildError(f"拒绝覆盖已存在的交叉编译产物：{destination}")
    executable = resolve_executable(go_executable)
    version = require_go_124(executable)
    try:
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination = destination.parent.resolve() / destination.name
    except OSError as error:
        raise CrossBuildError(f"无法准备产物目录：{error}") from error
    if os.path.lexists(destination):
        raise CrossBuildError(f"拒绝覆盖已存在的交叉编译产物：{destination}")

    environment = os.environ.copy()
    environment.update({"GOOS": goos, "GOARCH": goarch, "CGO_ENABLED": "0"})
    command = [
        executable,
        "build",
        "-trimpath",
        "-buildvcs=false",
        "-ldflags=-s -w",
        "-o",
        str(destination),
        "./cmd/launcher",
    ]
    try:
        result = run_process(
            command,
            cwd=LAUNCHER_ROOT,
            env=environment,
            timeout_seconds=600,
        )
        if result.returncode != 0:
            diagnostic = (result.stdout or "").strip()
            raise CrossBuildError(
                f"Go 交叉编译失败（{target}, {version}, exit={result.returncode}）\n{diagnostic}"
            )
        if not destination.is_file() or destination.stat().st_size == 0:
            raise CrossBuildError(f"Go 编译器未生成非空产物：{destination}")
        return destination
    except (OSError, RuntimeError) as error:
        if destination.is_file() or destination.is_symlink():
            destination.unlink(missing_ok=True)
        raise CrossBuildError(f"Go 交叉编译未完成（{target}）：{error}") from error
    except BaseException:
        if destination.is_file() or destination.is_symlink():
            destination.unlink(missing_ok=True)
        raise


def argument_parser() -> argparse.ArgumentParser:
    """集中声明 kebab-case 参数，让 CI 与本地调用共享同一接口。"""
    parser = argparse.ArgumentParser(
        description="使用 Go 1.24+ 为受支持的操作系统与架构交叉编译 XJar Plus 启动器。"
    )
    parser.add_argument("--target", required=True, type=parse_target, metavar="OS-ARCH",
                        help="目标平台：windows|linux|darwin 与 amd64|arm64 的组合。")
    parser.add_argument("--go-executable", default="go", metavar="PATH",
                        help="Go 编译器可执行路径；默认从 PATH 查找 go。")
    parser.add_argument("--output", type=Path, metavar="FILE",
                        help="单个启动器产物路径；默认写入 launcher/target/。已存在时拒绝覆盖。")
    return parser


def main(argv: list[str] | None = None) -> int:
    """将参数错误和构建错误转换为稳定退出码，供工作流直接调用。"""
    parser = argument_parser()
    args = parser.parse_args(argv)
    target = "-".join(args.target)
    try:
        artifact = build(target, args.go_executable, args.output)
    except CrossBuildError as error:
        print(f"错误：{error}", file=sys.stderr)
        return 1
    print(f"交叉编译完成：{target} -> {artifact}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
