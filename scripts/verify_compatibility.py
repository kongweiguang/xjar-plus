#!/usr/bin/env python3
# @author kongweiguang
"""构建锁定的可执行 JAR fixtures，并以真实运行测试矩阵兼容性。"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Sequence

from _delivery_common import ChineseArgumentParser, ChineseHelpFormatter, resolve_executable, run_checked, run_process


JAVA_VERSION = re.compile(r'\bversion\s+"(?:1\.)?(\d+)(?:[._+\-]|")', re.IGNORECASE)
PARENT_ARTIFACTS = {
    "springBoot": "spring-boot-starter-parent",
    "solon": "solon-parent",
}


class CompatibilityError(RuntimeError):
    """表示矩阵、JDK、临时 fixture 或验证命令不满足契约。"""


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    """提供与旧 PowerShell 入口等价的参数名，并默认从脚本位置定位仓库。"""
    root = Path(__file__).resolve().parent.parent
    parser = ChineseArgumentParser(
        description="构建版本锁定的普通 JAR、Spring Boot 和 Solon fixture 并运行兼容性验证。",
        formatter_class=ChineseHelpFormatter,
    )
    parser.add_argument("--framework", choices=("all", "plain", "boot", "solon"), default="all",
                        help="要验证的归档类型，默认全部。")
    parser.add_argument("--boot-version", help="只构建矩阵中指定的 Spring Boot 版本。")
    for major in (8, 11, 17, 21, 25):
        parser.add_argument(f"--java{major}", help=f"Java {major} 可执行文件或 JDK 根目录；不指定时读取 XJP_JAVA{major} 或 PATH。")
    parser.add_argument("--build-only", action="store_true", help="只生成 fixture，不运行 reactor 集成测试。")
    parser.add_argument("--maven-command", default="mvn", help="Maven 可执行命令或路径，默认 mvn。")
    parser.add_argument("--root", type=Path, default=root, help=argparse.SUPPRESS)
    return parser.parse_args(argv)


def safe_parse_xml(path: Path) -> ET.ElementTree:
    """拒绝 DTD 和实体声明后读取 Maven XML，避免工具读取外部资源。"""
    content = path.read_bytes()
    if b"<!DOCTYPE" in content.upper() or b"<!ENTITY" in content.upper():
        raise CompatibilityError(f"拒绝含 DTD 或实体声明的 XML：{path}")
    try:
        return ET.ElementTree(ET.fromstring(content))
    except ET.ParseError as exc:
        raise CompatibilityError(f"XML 格式无效：{path}: {exc}") from exc


def resolve_java(value: str, major: int) -> tuple[str, Path]:
    """解析 JDK 根目录或 Java 文件并核对真实版本，避免构建与运行用错 JVM。"""
    candidate = Path(value).expanduser()
    if candidate.is_dir():
        executable_name = "java.exe" if os.name == "nt" else "java"
        executable = candidate / "bin" / executable_name
    else:
        executable = Path(resolve_executable(value))
    if not executable.is_file():
        raise CompatibilityError(f"Java {major} 可执行文件不存在：{executable}")
    completed = run_process(
        [str(executable), "-version"],
        cwd=executable.parent,
        timeout_seconds=20,
    )
    if completed.returncode != 0:
        raise CompatibilityError(f"Java {major} 版本探测失败：{completed.stdout.strip()}")
    match = JAVA_VERSION.search(completed.stdout)
    if match is None or int(match.group(1)) != major:
        raise CompatibilityError(f"要求 Java {major}，检测结果为：{completed.stdout.strip()}")
    home = executable.resolve().parent.parent
    return str(executable.resolve()), home


def pin_parent_version(pom: Path, parent_artifact: str, version: str) -> Path:
    """只改临时 POM 中指定 parent 的版本，保留仓库 fixture 原文件与工作区状态。"""
    tree = safe_parse_xml(pom)
    project = tree.getroot()
    namespace = project.tag.partition("}")[0].lstrip("{") if "}" in project.tag else ""
    qname = (lambda name: f"{{{namespace}}}{name}") if namespace else (lambda name: name)
    parent = project.find(qname("parent"))
    if parent is None or parent.findtext(qname("artifactId"), "").strip() != parent_artifact:
        raise CompatibilityError(f"父 POM 模板中找不到指定 parent artifactId：{parent_artifact} ({pom})")
    version_node = parent.find(qname("version"))
    if version_node is None:
        raise CompatibilityError(f"父 POM 模板缺少 parent version：{pom}")
    version_node.text = version
    with tempfile.NamedTemporaryFile(prefix=".compatibility-", suffix=".xml", dir=pom.parent, delete=False) as stream:
        generated = Path(stream.name)
    try:
        tree.write(generated, encoding="utf-8", xml_declaration=True)
    except Exception:
        generated.unlink(missing_ok=True)
        raise
    return generated


def configure_boot_loader(pom: Path, implementation: str) -> None:
    """仅在临时 Boot POM 上设置 loaderImplementation，以同版本验证不同归档布局。"""
    tree = safe_parse_xml(pom)
    project = tree.getroot()
    namespace = project.tag.partition("}")[0].lstrip("{") if "}" in project.tag else ""
    qname = (lambda name: f"{{{namespace}}}{name}") if namespace else (lambda name: name)
    build = project.find(qname("build"))
    plugins = build.find(qname("plugins")) if build is not None else None
    if plugins is None:
        raise CompatibilityError(f"临时 POM 中找不到 Spring Boot 插件：{pom}")
    plugin = next((entry for entry in plugins.findall(qname("plugin"))
                   if entry.findtext(qname("groupId"), "").strip() == "org.springframework.boot"
                   and entry.findtext(qname("artifactId"), "").strip() == "spring-boot-maven-plugin"), None)
    if plugin is None:
        raise CompatibilityError(f"临时 POM 中找不到 Spring Boot repackage plugin：{pom}")
    configuration = plugin.find(qname("configuration"))
    if configuration is None:
        configuration = ET.SubElement(plugin, qname("configuration"))
    loader = configuration.find(qname("loaderImplementation"))
    if loader is None:
        loader = ET.SubElement(configuration, qname("loaderImplementation"))
    loader.text = implementation
    tree.write(pom, encoding="utf-8", xml_declaration=True)


def run_maven(command: str, args: Sequence[str], root: Path, java_home: Path) -> None:
    """为每次 Maven 调用显式指定 JVM 环境，避免切换 JDK 时污染父进程。"""
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = str(java_home / "bin") + os.pathsep + env.get("PATH", "")
    run_checked(command, args, cwd=root, env=env, stream=True, timeout_seconds=900)


def build_plain_fixture(root: Path, output: Path, maven: str, java_home: Path) -> None:
    """用当前 harness JDK 构建 Java 8 字节码 fixture，并复制到本次独立证据目录。"""
    pom = root / "integration-tests/fixtures/plain-java8/pom.xml"
    run_maven(maven, ["-B", "-ntp", "-f", str(pom), "package"], root, java_home)
    built = root / "integration-tests/fixtures/plain-java8/target/plain-java8-fixture.jar"
    if not built.is_file():
        raise CompatibilityError(f"普通 JAR fixture 未生成：{built}")
    (output / "plain-java8.jar").write_bytes(built.read_bytes())


def build_boot_fixtures(
    root: Path,
    entries: Sequence[dict[str, Any]],
    output: Path,
    maven: str,
    java_executables: dict[int, str],
    java_homes: dict[int, Path],
    *,
    loader_implementation: str | None = None,
) -> None:
    """为每个锁定 Boot 补丁构建标准可执行包，并隔离需要测试的 CLASSIC 布局变体。"""
    base_pom = root / "integration-tests/fixtures/spring-boot/pom.xml"
    for entry in entries:
        version = str(entry["version"])
        major = int(version.split(".", maxsplit=1)[0])
        runtime_major = int(entry.get("verificationJava", 8 if major == 2 else 21))
        if runtime_major not in java_executables:
            raise CompatibilityError(f"Spring Boot {version} 需要 Java {runtime_major}，但未配置对应 JDK。")
        java_level = "8" if major == 2 else "17"
        starter = "spring-boot-starter-webmvc" if major >= 4 else "spring-boot-starter-web"
        temporary_pom = pin_parent_version(base_pom, PARENT_ARTIFACTS["springBoot"], version)
        try:
            if loader_implementation:
                configure_boot_loader(temporary_pom, loader_implementation)
            maven_args = [
                "-B", "-ntp", "-f", str(temporary_pom), f"-Dboot.version={version}",
                f"-Dfixture.java.version={java_level}", f"-Dweb.starter={starter}", "clean", "package",
            ]
            run_maven(maven, maven_args, root, java_homes[runtime_major])
            built = root / f"integration-tests/fixtures/spring-boot/target/app-{version}.jar"
            if not built.is_file():
                raise CompatibilityError(f"Spring Boot {version} fixture 未生成：{built}")
            if loader_implementation:
                filename = f"boot-layout-{version}-{loader_implementation.lower()}.jar"
            else:
                filename = f"boot-{version}.jar"
            (output / filename).write_bytes(built.read_bytes())
        finally:
            temporary_pom.unlink(missing_ok=True)


def build_solon_fixtures(
    root: Path, entries: Sequence[dict[str, Any]], output: Path, maven: str, java_home: Path
) -> None:
    """构建 Solon 原生嵌套 JAR fixture，不用重新打包近似产物替代其 loader。"""
    base_pom = root / "integration-tests/fixtures/solon/pom.xml"
    for entry in entries:
        version = str(entry["version"])
        temporary_pom = pin_parent_version(base_pom, PARENT_ARTIFACTS["solon"], version)
        try:
            run_maven(maven, ["-B", "-ntp", "-f", str(temporary_pom), f"-Dsolon.version={version}", "package"], root, java_home)
            built = root / f"integration-tests/fixtures/solon/target/app-{version}.jar"
            if not built.is_file():
                raise CompatibilityError(f"Solon {version} fixture 未生成：{built}")
            (output / f"solon-{version}.jar").write_bytes(built.read_bytes())
        finally:
            temporary_pom.unlink(missing_ok=True)


def select_matrix_entries(matrix: dict[str, Any], framework: str, boot_version: str | None) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
    """仅返回当前选择涉及的 framework 行与 Boot 布局变体，防止误跑或误消费旧 fixture。"""
    run_plain = framework in ("all", "plain")
    run_boot = framework in ("all", "boot")
    run_solon = framework in ("all", "solon")
    if boot_version and not run_boot:
        raise CompatibilityError("--boot-version 只能与 --framework boot 或 all 一起使用。")
    boot_entries = list(matrix.get("springBoot", [])) if run_boot else []
    if boot_version:
        boot_entries = [entry for entry in boot_entries if entry.get("version") == boot_version]
        if not boot_entries:
            raise CompatibilityError(f"Boot 版本不在锁定矩阵中：{boot_version}")
    solon_entries = list(matrix.get("solon", [])) if run_solon else []
    selected_versions = {str(entry.get("version")) for entry in boot_entries}
    layout_variants = [
        entry for entry in matrix.get("layoutVariants", [])
        if run_boot and entry.get("framework") == "springBoot" and str(entry.get("version")) in selected_versions
    ]
    return boot_entries, solon_entries, layout_variants


def choose_java_paths(args: argparse.Namespace, root: Path, boot_entries: Sequence[dict[str, Any]], framework: str) -> tuple[dict[int, str], dict[int, Path]]:
    """只要求所选矩阵必需的 JDK，并将可选 plain-JAR 运行时精确传入测试。"""
    configured = {major: getattr(args, f"java{major}") or os.environ.get(f"XJP_JAVA{major}") for major in (8, 11, 17, 21, 25)}
    run_plain = framework in ("all", "plain")
    required = {21}
    if run_plain or any(int(entry.get("verificationJava", 21)) == 8 for entry in boot_entries):
        required.add(8)
    selected: dict[int, str] = {}
    homes: dict[int, Path] = {}
    for major in (8, 11, 17, 21, 25):
        if major not in required and not (run_plain and configured[major]):
            continue
        value = configured[major] or "java"
        selected[major], homes[major] = resolve_java(value, major)
    return selected, homes


def invoke_tests(
    root: Path,
    fixture_dir: Path,
    maven: str,
    java_home: Path,
    java_executables: dict[int, str],
    framework: str,
    boot_count: int,
    solon_count: int,
    has_layout_variants: bool,
) -> None:
    """向 reactor 集成测试传递精确 suite、fixture 数量和 JVM 路径，避免矩阵部分运行误判。"""
    properties = {
        "xjp.compatibility.fixtureDir": str(fixture_dir),
        "xjp.compatibility.plain": str(framework in ("all", "plain")).lower(),
        "xjp.compatibility.boot": str(framework in ("all", "boot") and boot_count > 0).lower(),
        "xjp.compatibility.boot.layoutVariants": str(has_layout_variants).lower(),
        "xjp.compatibility.solon": str(framework in ("all", "solon") and solon_count > 0).lower(),
        "xjp.expected.bootFixtures": str(boot_count),
        "xjp.expected.solonFixtures": str(solon_count),
    }
    command = ["-B", "-ntp", "-Pintegration", "-pl", "integration-tests", "-am", "verify"]
    command.extend(f"-D{key}={value}" for key, value in properties.items())
    command.extend(f"-Dxjp.java{major}={path}" for major, path in sorted(java_executables.items()))
    run_maven(maven, command, root, java_home)


def run_compatibility(args: argparse.Namespace) -> Path:
    """按版本矩阵创建唯一 fixture 目录，再选择性运行兼容性集成测试。"""
    root = args.root.resolve()
    matrix_path = root / "docs/compatibility-matrix.json"
    if not matrix_path.is_file():
        raise CompatibilityError(f"兼容矩阵不存在：{matrix_path}")
    matrix = json.loads(matrix_path.read_text(encoding="utf-8"))
    boot_entries, solon_entries, layout_variants = select_matrix_entries(matrix, args.framework, args.boot_version)
    java_executables, java_homes = choose_java_paths(args, root, boot_entries, args.framework)
    maven = resolve_executable(args.maven_command)
    run_plain = args.framework in ("all", "plain")
    fixture_dir = root / "integration-tests/target" / f"compatibility-fixtures-{uuid.uuid4().hex}"
    fixture_dir.mkdir(parents=True, exist_ok=False)
    print(f"正在构建所选可执行 JAR fixtures：{fixture_dir}")
    if run_plain:
        build_plain_fixture(root, fixture_dir, maven, java_homes[21])
    if boot_entries:
        build_boot_fixtures(root, boot_entries, fixture_dir, maven, java_executables, java_homes)
    for variant in layout_variants:
        build_boot_fixtures(
            root, [variant], fixture_dir, maven, java_executables, java_homes,
            loader_implementation=str(variant["loaderImplementation"]),
        )
    if solon_entries:
        build_solon_fixtures(root, solon_entries, fixture_dir, maven, java_homes[21])
    if args.build_only:
        print("仅构建 fixtures；未启动 reactor 集成测试。")
        print(f"XJP_FIXTURE_DIR={fixture_dir}")
        return fixture_dir
    invoke_tests(
        root, fixture_dir, maven, java_homes[21], java_executables, args.framework,
        len(boot_entries), len(solon_entries), bool(layout_variants),
    )
    return fixture_dir


def main(argv: Sequence[str] | None = None) -> int:
    """把输入与工具失败转换为简洁中文诊断，并保留非零退出码供 CI 门禁使用。"""
    args = parse_args(argv)
    try:
        run_compatibility(args)
    except (CompatibilityError, FileNotFoundError, OSError, RuntimeError, ValueError) as exc:
        print(f"兼容性验证失败：{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
