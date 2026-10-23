#!/usr/bin/env python3
# @author kongweiguang
"""检查 Maven 模块依赖方向和 DDD 包导入边界。"""

from __future__ import annotations

import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Optional, Sequence


GROUP_ID_TOKEN = re.compile(r"\$\{([^}]+)\}")
JAVA_PACKAGE = re.compile(r"^[ \t]*package[ \t]+([A-Za-z0-9_.]+)[ \t]*;", re.MULTILINE)


class CheckError(RuntimeError):
    """表示架构策略不满足或配置文件无效。"""


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
    """解析可选仓库路径；默认值从脚本位置计算，不依赖当前工作目录。"""
    script_root = Path(__file__).resolve().parent.parent
    parser = ChineseArgumentParser(
        description="检查 Maven 模块依赖方向、DDD 分层导入边界和架构配置。",
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


def local_name(tag: str) -> str:
    """移除 XML 命名空间，以便架构检查不受 Maven POM 命名空间版本影响。"""
    return tag.rsplit("}", 1)[-1]


def read_safe_xml(path: Path) -> ET.Element:
    """拒绝 DTD 和实体声明后再解析 POM，避免读取仓库输入时加载外部资源。"""
    data = path.read_bytes()
    if re.search(rb"<!\s*(?:DOCTYPE|ENTITY)\b", data, re.IGNORECASE):
        raise CheckError(f"POM 不允许包含 DTD 或实体声明：{path}")
    try:
        return ET.fromstring(data)
    except ET.ParseError as failure:
        raise CheckError(f"无法解析 XML 文件 {path}：{failure}") from failure


def child(node: ET.Element, name: str) -> Optional[ET.Element]:
    """按本地标签名查找直接子节点，避免把插件或依赖管理节点误当作模块声明。"""
    return next((item for item in node if local_name(item.tag) == name), None)


def children(node: ET.Element, name: str) -> list[ET.Element]:
    """返回名称匹配的直接子节点，保持 POM 中声明顺序且不跨越层级。"""
    return [item for item in node if local_name(item.tag) == name]


def node_text(node: Optional[ET.Element], name: str) -> str:
    """读取直接子节点文本并去除 POM 格式空白，不执行 Maven 插值。"""
    if node is None:
        return ""
    value = child(node, name)
    return "" if value is None or value.text is None else value.text.strip()


def pom_properties(project: ET.Element) -> dict[str, str]:
    """读取项目自身属性，为后续确定性的有限轮次插值准备输入。"""
    properties_node = child(project, "properties")
    if properties_node is None:
        return {}
    return {
        local_name(prop.tag).casefold(): (prop.text or "").strip()
        for prop in properties_node
        if isinstance(prop.tag, str)
    }


def resolve_pom_value(
    value: str,
    properties: dict[str, str],
    project_group_id: str,
    project_version: str,
) -> str:
    """只解析项目坐标和已知属性，避免执行 Maven profile 或外部插值。"""
    resolved = value
    for _ in range(16):
        previous = resolved

        def replace(match: re.Match[str]) -> str:
            """替换一个已知 Maven 占位符，未知属性保持原样以便后续校验暴露问题。"""
            token = match.group(1)
            if token in ("project.groupId", "pom.groupId"):
                return project_group_id
            if token in ("project.version", "pom.version"):
                return project_version
            return properties.get(token.casefold(), match.group(0))

        resolved = GROUP_ID_TOKEN.sub(replace, resolved)
        if resolved == previous:
            break
    return resolved.strip()


def module_dependencies(
    module_path: Path,
    artifact_id: str,
    allowed: Sequence[str],
    known_modules: dict[str, Path],
    root_properties: dict[str, str],
    root_group_id: str,
    root_version: str,
) -> None:
    """在 Maven 依赖边界检查模块方向，防止上层被底层实现反向依赖。"""
    document = read_safe_xml(module_path / "pom.xml")
    properties = dict(root_properties)
    properties.update(pom_properties(document))
    declared_group_id = node_text(document, "groupId")
    if not declared_group_id:
        declared_group_id = node_text(child(document, "parent"), "groupId")
    if not declared_group_id:
        declared_group_id = root_group_id
    effective_group_id = resolve_pom_value(
        declared_group_id, properties, root_group_id, root_version
    )
    dependency_root = child(document, "dependencies")
    if dependency_root is None:
        return
    for dependency in children(dependency_root, "dependency"):
        dependency_artifact = node_text(dependency, "artifactId")
        dependency_group = resolve_pom_value(
            node_text(dependency, "groupId"),
            properties,
            effective_group_id,
            root_version,
        )
        if (
            dependency_group == "io.github.kongweiguang.xjar.plus"
            and dependency_artifact in known_modules
            and dependency_artifact not in allowed
        ):
            raise CheckError(f"模块依赖方向违规：{artifact_id} -> {dependency_artifact}")


def java_production_files(root: Path) -> list[Path]:
    """只枚举仓库 Java 生产源码，排除测试、生成目录和无关 Java 文件。"""
    files: list[Path] = []
    for path in root.rglob("*"):
        parts = path.relative_to(root).parts
        normalized_parts = tuple(part.casefold() for part in parts)
        if path.is_file() and path.suffix.casefold() == ".java":
            if any(
                normalized_parts[index : index + 3] == ("src", "main", "java")
                for index in range(len(normalized_parts) - 2)
            ) and "target" not in normalized_parts:
                files.append(path)
    return sorted(files)


def assert_ddd_imports(root: Path, rules: dict[str, Sequence[str]]) -> None:
    """检查 DDD 包的禁止导入方向，同时允许包私有实现自由组织。"""
    namespace_prefix = "io.github.kongweiguang.xjar.plus."
    violations: list[str] = []
    for source in java_production_files(root):
        content = source.read_text(encoding="utf-8")
        package_match = JAVA_PACKAGE.search(content)
        if package_match is None:
            continue
        package_name = package_match.group(1)
        for layer, forbidden_layers in rules.items():
            if not re.search(rf"(?:^|\.){re.escape(layer)}(?:\.|$)", package_name, re.IGNORECASE):
                continue
            for forbidden_layer in forbidden_layers:
                pattern = re.compile(
                    rf"^[ \t]*import[ \t]+(?:static[ \t]+)?"
                    rf"{re.escape(namespace_prefix)}{re.escape(forbidden_layer)}(?:\.|\*|;)",
                    re.MULTILINE,
                )
                for _ in pattern.finditer(content):
                    violations.append(
                        f"{source}: 分层 {layer} 禁止导入 DDD 层 {forbidden_layer}"
                    )
    if violations:
        raise CheckError("DDD 包导入方向违规：\n" + "\n".join(violations))


def check_repository(root: Path) -> int:
    """验证根坐标、必需模块、模块依赖和 DDD 导入边界，返回模块总数。"""
    root = root.resolve(strict=True)
    rules_path = root / "config" / "quality" / "architecture.json"
    try:
        rules = json.loads(rules_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as failure:
        raise CheckError(f"无法读取架构规则文件 {rules_path}：{failure}") from failure
    if not isinstance(rules, dict):
        raise CheckError(f"架构规则文件必须是 JSON 对象：{rules_path}")
    if not isinstance(rules.get("groupId"), str) or not isinstance(rules.get("version"), str):
        raise CheckError("架构规则必须包含字符串类型的 groupId 和 version。")

    parent_project = read_safe_xml(root / "pom.xml")
    root_properties = pom_properties(parent_project)
    raw_group_id = node_text(parent_project, "groupId")
    raw_version = node_text(parent_project, "version")
    root_version = resolve_pom_value(raw_version, root_properties, raw_group_id, raw_version)
    root_group_id = resolve_pom_value(raw_group_id, root_properties, raw_group_id, root_version)
    if root_group_id != rules.get("groupId"):
        raise CheckError(
            f"根 Maven groupId 应为 {rules.get('groupId')}，实际为 {root_group_id!r}。"
        )
    if root_version != rules.get("version"):
        raise CheckError(
            f"根 Maven version 应为 {rules.get('version')}，实际为 {root_version!r}。"
        )

    modules_node = child(parent_project, "modules")
    declared_modules = {
        (module.text or "").strip()
        for module in children(modules_node, "module")
    } if modules_node is not None else set()
    required_modules = rules.get("requiredModules")
    if (
        not isinstance(required_modules, list)
        or not required_modules
        or any(not isinstance(module, str) or not module for module in required_modules)
        or len(set(required_modules)) != len(required_modules)
    ):
        raise CheckError("架构规则必须列出不重复且非空的 requiredModules 字符串。")
    missing_modules = [module for module in required_modules if module not in declared_modules]
    if missing_modules:
        raise CheckError("Maven reactor 缺少必需模块：" + ", ".join(missing_modules))

    known_modules: dict[str, Path] = {}
    for module in required_modules:
        module_path = root / module
        module_pom = module_path / "pom.xml"
        if not module_pom.is_file():
            raise CheckError(f"缺少必需模块 POM：{module}/pom.xml")
        artifact_id = node_text(read_safe_xml(module_pom), "artifactId")
        if artifact_id != module:
            raise CheckError(
                f"模块目录 {module!r} 的 artifactId 应为 {module!r}，实际为 {artifact_id!r}。"
            )
        known_modules[artifact_id] = module_path

    dependency_rules = rules.get("moduleDependencies")
    if not isinstance(dependency_rules, dict):
        raise CheckError("架构规则必须包含 moduleDependencies 对象。")
    for module in required_modules:
        allowed = dependency_rules.get(module)
        if not isinstance(allowed, list) or any(not isinstance(item, str) for item in allowed):
            raise CheckError(f"架构规则缺少模块 {module} 的字符串依赖白名单。")
        module_dependencies(
            known_modules[module],
            module,
            allowed,
            known_modules,
            root_properties,
            root_group_id,
            root_version,
        )

    ddd_rules = rules.get("dddForbiddenImports")
    if not isinstance(ddd_rules, dict) or any(
        not isinstance(layer, str)
        or not isinstance(forbidden, list)
        or any(not isinstance(item, str) for item in forbidden)
        for layer, forbidden in ddd_rules.items()
    ):
        raise CheckError("架构规则必须包含 dddForbiddenImports 对象。")
    assert_ddd_imports(root, ddd_rules)
    return len(known_modules)


def main(argv: Optional[Sequence[str]] = None) -> int:
    """运行架构门禁，并将策略错误转换为简明中文提示和非零退出码。"""
    configure_console_encoding()
    args = parse_args(argv)
    try:
        module_count = check_repository(args.root)
    except (CheckError, OSError, UnicodeError) as failure:
        print(f"架构检查失败：{failure}", file=sys.stderr)
        return 1
    print(f"架构检查通过：已验证 {module_count} 个 Maven 模块及 DDD 依赖方向。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
