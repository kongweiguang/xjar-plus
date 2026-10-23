#!/usr/bin/env python3
# @author kongweiguang
"""检查 Java 与 Go 生产源码的包名及 AES-GCM 唯一套件约束。"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import Optional, Sequence


NAMESPACE = "io.github.kongweiguang.xjar.plus"
JAVA_MODULES = (
    "xjar-plus-format",
    "xjar-plus-runtime",
    "xjar-plus-runtime-boot-modern",
    "xjar-plus-core",
    "xjar-plus-app",
)
FORBIDDEN_CIPHER = re.compile(
    r"AES\s*/\s*(?:CBC|ECB|CTR|OFB|CFB)\s*/|\bDES(?:ede)?\b|PBEWithMD5AndDES",
    re.IGNORECASE,
)
JAVA_PACKAGE = re.compile(r"^[ \t]*package[ \t]+([A-Za-z0-9_.]+)[ \t]*;", re.MULTILINE)


class CheckError(RuntimeError):
    """表示源码策略检查失败或输入目录不完整。"""


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
    """解析可选仓库路径；默认值始终从脚本位置计算，不依赖当前工作目录。"""
    script_root = Path(__file__).resolve().parent.parent
    parser = ChineseArgumentParser(
        description="检查项目 Java 包名、Go 源码范围和唯一 AES-GCM 加密套件。",
        add_help=False,
        formatter_class=ChineseHelpFormatter,
    )
    parser.add_argument("-h", "--help", action="help", help="显示此帮助信息并退出。")
    parser.add_argument(
        "--root",
        type=Path,
        default=script_root,
        help="仓库根目录，默认使用本脚本所在仓库。",
    )
    return parser.parse_args(argv)


def configure_console_encoding() -> None:
    """将可重配置的标准输出固定为 UTF-8，避免 Windows 代码页破坏中文提示。"""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if callable(reconfigure):
            reconfigure(encoding="utf-8", errors="replace")


def java_sources(root: Path) -> list[Path]:
    """仅收集五个 Maven 模块的手写生产源码，排除测试与 target 生成物。"""
    sources: list[Path] = []
    for module in JAVA_MODULES:
        source_root = root / module / "src" / "main" / "java"
        if source_root.is_dir():
            sources.extend(
                path for path in source_root.rglob("*") if path.is_file() and path.suffix.casefold() == ".java"
            )
    return sorted(sources)


def go_sources(root: Path) -> list[Path]:
    """只收集 launcher 下的 Go 生产文件，排除 vendor 和测试源码。"""
    launcher_root = root / "launcher"
    if not launcher_root.is_dir():
        raise CheckError(f"缺少 Go 模块目录：{launcher_root}")
    sources = [
        path
        for path in launcher_root.rglob("*")
        if path.is_file()
        and "vendor" not in {part.casefold() for part in path.relative_to(launcher_root).parts}
        and path.suffix.casefold() == ".go"
        and not path.name.casefold().endswith("_test.go")
    ]
    return sorted(sources)


def assert_no_forbidden_cipher(sources: Sequence[Path]) -> None:
    """拒绝遗留或未认证算法引用，避免唯一 AES-GCM 套件被旁路或降级。"""
    violations: list[str] = []
    for source in sources:
        for line_number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), start=1):
            if FORBIDDEN_CIPHER.search(line):
                violations.append(f"{source}:{line_number}: 存在不支持的算法引用：{line.strip()}")
    if violations:
        raise CheckError("发现不支持的加密算法引用：\n" + "\n".join(violations))


def assert_java_namespace(sources: Sequence[Path]) -> None:
    """要求每个 Java 生产文件声明统一包名前缀，避免模块间出现旧 API 包。"""
    violations: list[str] = []
    for source in sources:
        package_match = JAVA_PACKAGE.search(source.read_text(encoding="utf-8"))
        package_name = package_match.group(1) if package_match else ""
        if package_name != NAMESPACE and not package_name.startswith(f"{NAMESPACE}."):
            violations.append(f"{source}: 缺少统一包名或包名不符合约定")
    if violations:
        raise CheckError(f"Java 包名必须以 {NAMESPACE} 开头：\n" + "\n".join(violations))


def assert_gcm_implementations(java_files: Sequence[Path], go_files: Sequence[Path]) -> None:
    """确保 Java 和 Go 两侧都保留对应 AES-GCM 实现，防止检查退化为单语言门禁。"""
    java_found = any(
        re.search(r"AES/GCM/NoPadding", source.read_text(encoding="utf-8"), re.IGNORECASE)
        for source in java_files
    )
    go_found = any(
        re.search(r"cipher\.NewGCM", source.read_text(encoding="utf-8"), re.IGNORECASE)
        for source in go_files
    )
    if not java_found or not go_found:
        raise CheckError("Java 必须保留 AES/GCM/NoPadding，Go 必须保留 cipher.NewGCM 实现。")


def check_repository(root: Path) -> tuple[int, int]:
    """执行全部源码策略并返回 Java、Go 生产文件数量供命令行汇总。"""
    root = root.resolve(strict=True)
    if not (root / "pom.xml").is_file():
        raise CheckError(f"仓库根目录中缺少 pom.xml：{root}")
    java_files = java_sources(root)
    go_files = go_sources(root)
    if not java_files:
        raise CheckError("五个必需模块中没有 Java 生产源码文件。")
    if not go_files:
        raise CheckError("launcher 目录中没有 Go 生产源码文件。")
    assert_java_namespace(java_files)
    assert_no_forbidden_cipher([*java_files, *go_files])
    assert_gcm_implementations(java_files, go_files)
    return len(java_files), len(go_files)


def main(argv: Optional[Sequence[str]] = None) -> int:
    """运行源码策略门禁，并将失败收敛为简明中文错误信息和非零退出码。"""
    configure_console_encoding()
    args = parse_args(argv)
    try:
        java_count, go_count = check_repository(args.root)
    except (CheckError, OSError, UnicodeError) as failure:
        print(f"源码策略检查失败：{failure}", file=sys.stderr)
        return 1
    print(f"源码策略检查通过：Java {java_count} 个、Go {go_count} 个生产文件；仅使用 AES-GCM。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
