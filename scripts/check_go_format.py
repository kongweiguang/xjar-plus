#!/usr/bin/env python3
# @author kongweiguang
"""以只读方式检查 launcher 中的 Go 源码是否符合 gofmt。"""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Optional, Sequence


class CheckError(RuntimeError):
    """表示 Go 格式检查失败或 launcher 输入目录不完整。"""


class ChineseHelpFormatter(argparse.HelpFormatter):
    """将命令行帮助中的默认用法标题本地化为中文。"""

    def add_usage(self, usage, actions, groups, prefix=None):
        """使用中文标题展示用法，同时保留 argparse 的参数布局。"""
        if prefix is None:
            prefix = "用法："
        return super().add_usage(usage, actions, groups, prefix)


class ChineseArgumentParser(argparse.ArgumentParser):
    """提供中文帮助分组和中文参数错误提示。"""

    def __init__(self, *args, **kwargs):
        """将标准 argparse 分组标题改为中文，便于在各平台获得一致帮助界面。"""
        super().__init__(*args, **kwargs)
        self._optionals.title = "选项"

    def error(self, message):
        """用中文报告参数错误，并仍使用标准退出码表示命令行输入无效。"""
        self.print_usage(sys.stderr)
        self.exit(2, f"{self.prog}: 错误：{message}\n")


def parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    """解析可选 launcher 目录；默认值从脚本位置计算，不依赖当前工作目录。"""
    repository_root = Path(__file__).resolve().parent.parent
    parser = ChineseArgumentParser(
        description="只读检查 launcher 中受版本控制的 Go 源码是否符合 gofmt。",
        add_help=False,
        formatter_class=ChineseHelpFormatter,
    )
    parser.add_argument("-h", "--help", action="help", help="显示此帮助信息并退出。")
    parser.add_argument(
        "--launcher-root",
        type=Path,
        default=repository_root / "launcher",
        help="Go launcher 模块目录，默认使用仓库中的 launcher。",
    )
    return parser.parse_args(argv)


def configure_console_encoding() -> None:
    """将可重配置的标准输出固定为 UTF-8，避免 Windows 代码页破坏中文提示。"""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            reconfigure(encoding="utf-8", errors="replace")


def go_sources(launcher_root: Path) -> list[Path]:
    """仅收集 launcher 的维护源码，并排除测试、依赖副本和生成目录。"""
    launcher_root = launcher_root.resolve(strict=True)
    if not (launcher_root / "go.mod").is_file():
        raise CheckError(f"launcher 模块缺少 go.mod：{launcher_root}")
    excluded = {"vendor", "target", "bin"}
    files = [
        path
        for path in launcher_root.rglob("*")
        if path.is_file()
        and path.suffix.casefold() == ".go"
        and not path.name.casefold().endswith("_test.go")
        and not excluded.intersection(part.casefold() for part in path.relative_to(launcher_root).parts)
    ]
    if not files:
        raise CheckError(f"launcher 模块中没有 Go 源码文件：{launcher_root}")
    return sorted(files)


def assert_formatted(files: Sequence[Path], formatter: str) -> None:
    """用 gofmt 的列表模式检查格式，不改写贡献者工作区中的任何文件。"""
    command = [formatter, "-l", *(str(path) for path in files)]
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            check=False,
            encoding="utf-8",
            errors="replace",
            shell=False,
            text=True,
        )
    except OSError as failure:
        raise CheckError(f"无法运行 gofmt：{failure}") from failure
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise CheckError(f"检查 launcher 源码时 gofmt 执行失败：{detail}")
    drift = [line for line in result.stdout.splitlines() if line.strip()]
    if drift:
        raise CheckError(
            "发现 Go 源码格式不符合 gofmt；请对以下文件执行 gofmt：\n" + "\n".join(drift)
        )


def check_launcher(launcher_root: Path, formatter: Optional[str] = None) -> int:
    """检查一个 launcher 模块并返回源码数量，允许测试直接注入格式器路径。"""
    resolved_formatter = formatter or shutil.which("gofmt")
    if not resolved_formatter:
        raise CheckError("PATH 中未找到 gofmt；请先安装 Go 工具链。")
    files = go_sources(launcher_root)
    assert_formatted(files, resolved_formatter)
    return len(files)


def main(argv: Optional[Sequence[str]] = None) -> int:
    """运行只读格式门禁，并将检查失败转换为中文提示和非零退出码。"""
    configure_console_encoding()
    args = parse_args(argv)
    try:
        file_count = check_launcher(args.launcher_root)
    except (CheckError, OSError) as failure:
        print(f"Go 格式检查失败：{failure}", file=sys.stderr)
        return 1
    print(f"Go 格式检查通过：检查了 {file_count} 个生产源码文件，未修改文件。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
