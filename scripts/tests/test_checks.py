# @author kongweiguang
"""验证源码策略、DDD 架构规则和 Go 格式门禁的正反例。"""

from __future__ import annotations

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


SCRIPTS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_ROOT))

import check_architecture
import check_go_format
import check_source


MODULES = (
    "xjar-plus-format",
    "xjar-plus-runtime",
    "xjar-plus-runtime-boot-modern",
    "xjar-plus-core",
    "xjar-plus-app",
)
GROUP_ID = "io.github.kongweiguang.xjar.plus"
ARCHITECTURE_RULES = {
    "groupId": GROUP_ID,
    "version": "2.0.0",
    "requiredModules": list(MODULES),
    "moduleDependencies": {
        "xjar-plus-format": [],
        "xjar-plus-runtime": ["xjar-plus-format"],
        "xjar-plus-runtime-boot-modern": ["xjar-plus-format", "xjar-plus-runtime"],
        "xjar-plus-core": ["xjar-plus-format", "xjar-plus-runtime", "xjar-plus-runtime-boot-modern"],
        "xjar-plus-app": [
            "xjar-plus-format",
            "xjar-plus-runtime",
            "xjar-plus-runtime-boot-modern",
            "xjar-plus-core",
        ],
    },
    "dddForbiddenImports": {
        "domain": ["application", "infrastructure", "interfaces"],
        "application": ["infrastructure", "interfaces"],
    },
}


def write_text(path: Path, content: str) -> Path:
    """创建 UTF-8 测试输入并确保父目录存在，避免测试依赖本地工作区布局。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def make_source_root(root: Path) -> Path:
    """创建满足源码门禁的最小仓库，并加入会被规则排除的生成及测试文件。"""
    write_text(root / "pom.xml", "<project />")
    for module in MODULES:
        source = root / module / "src" / "main" / "java" / "sample" / "Sample.java"
        package = f"{GROUP_ID}.{module.replace('-', '.')}.sample"
        extra = 'String cipher = "AES/GCM/NoPadding";' if module == "xjar-plus-format" else ""
        write_text(source, f"package {package}; class Sample {{ {extra} }}\n")
    write_text(root / "launcher" / "go.mod", "module example.invalid/check\n")
    write_text(root / "launcher" / "cmd" / "main.go", "package main\n// cipher.NewGCM\n")
    write_text(
        root / "xjar-plus-app" / "target" / "Generated.java",
        'package wrong; class Generated { String old = "AES/CBC/PKCS5Padding"; }\n',
    )
    write_text(root / "launcher" / "internal" / "sample_test.go", 'package sample\n// AES/CBC/PKCS5Padding\n')
    write_text(root / "launcher" / "vendor" / "dependency" / "vendor.go", 'package dependency\n// DES\n')
    return root


def make_architecture_root(root: Path) -> Path:
    """生成使用 Maven 命名空间与属性插值的最小五模块 DDD 项目。"""
    module_xml = "".join(f"<module>{module}</module>" for module in MODULES)
    write_text(
        root / "pom.xml",
        f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <groupId>${{root.group}}</groupId><artifactId>parent</artifactId><version>${{root.version}}</version>
  <properties><root.group>{GROUP_ID}</root.group><root.version>2.0.0</root.version></properties>
  <modules>{module_xml}</modules>
</project>""",
    )
    write_text(
        root / "config" / "quality" / "architecture.json",
        json.dumps(ARCHITECTURE_RULES),
    )
    for module in MODULES:
        allowed = ARCHITECTURE_RULES["moduleDependencies"][module]
        dependencies = "".join(
            f"<dependency><groupId>${{project.groupId}}</groupId><artifactId>{artifact}</artifactId></dependency>"
            for artifact in allowed
        )
        write_text(
            root / module / "pom.xml",
            f"""<project xmlns="http://maven.apache.org/POM/4.0.0">
  <parent><groupId>${{project.groupId}}</groupId><version>${{project.version}}</version></parent>
  <artifactId>{module}</artifactId><dependencies>{dependencies}</dependencies>
</project>""",
        )
    write_text(
        root / "xjar-plus-core" / "src" / "main" / "java" / "sample" / "Domain.java",
        f"package {GROUP_ID}.domain;\nclass Domain {{}}\n",
    )
    return root


class SourceCheckTests(unittest.TestCase):
    """覆盖源码门禁的目录边界、包名约束和算法策略。"""

    def test_accepts_required_sources_and_ignores_generated_or_test_files(self):
        """确认合法包名与 Java、Go GCM 实现通过，且生成目录和测试不参与生产扫描。"""
        with tempfile.TemporaryDirectory() as directory:
            root = make_source_root(Path(directory))
            self.assertEqual((5, 1), check_source.check_repository(root))

    def test_rejects_forbidden_cipher_in_production_source(self):
        """确认生产源码引用 CBC 时门禁失败，而同一文本在被排除目录中不触发失败。"""
        with tempfile.TemporaryDirectory() as directory:
            root = make_source_root(Path(directory))
            source = root / MODULES[0] / "src" / "main" / "java" / "sample" / "Sample.java"
            source.write_text(source.read_text(encoding="utf-8") + "// AES/CBC/PKCS5Padding\n", encoding="utf-8")
            with self.assertRaisesRegex(check_source.CheckError, "不支持的加密算法"):
                check_source.check_repository(root)

    def test_rejects_wrong_java_package(self):
        """确认旧包名或意外包名不能因其他模块正确而绕过统一命名空间检查。"""
        with tempfile.TemporaryDirectory() as directory:
            root = make_source_root(Path(directory))
            source = root / MODULES[0] / "src" / "main" / "java" / "sample" / "Sample.java"
            source.write_text("package legacy.sample; class Sample {}\n", encoding="utf-8")
            with self.assertRaisesRegex(check_source.CheckError, "Java 包名"):
                check_source.check_repository(root)

    def test_requires_both_gcm_implementations(self):
        """确认 Java 与 Go 任一侧缺少对应 GCM 实现都会阻断源码门禁。"""
        with tempfile.TemporaryDirectory() as directory:
            root = make_source_root(Path(directory))
            go_file = root / "launcher" / "cmd" / "main.go"
            go_file.write_text("package main\n", encoding="utf-8")
            with self.assertRaisesRegex(check_source.CheckError, "cipher.NewGCM"):
                check_source.check_repository(root)


class ArchitectureCheckTests(unittest.TestCase):
    """覆盖 Maven 模块依赖、XML 输入安全和 DDD 导入边界。"""

    def test_accepts_interpolated_maven_coordinates_and_allowed_edges(self):
        """确认命名空间无关解析、项目属性插值和已允许的模块依赖可以通过。"""
        with tempfile.TemporaryDirectory() as directory:
            root = make_architecture_root(Path(directory))
            self.assertEqual(len(MODULES), check_architecture.check_repository(root))

    def test_rejects_forbidden_module_dependency(self):
        """确认底层模块依赖上层模块时在 Maven 依赖边界被拒绝。"""
        with tempfile.TemporaryDirectory() as directory:
            root = make_architecture_root(Path(directory))
            pom = root / MODULES[0] / "pom.xml"
            content = pom.read_text(encoding="utf-8").replace(
                "</dependencies>",
                f"<dependency><groupId>{GROUP_ID}</groupId><artifactId>xjar-plus-core</artifactId></dependency></dependencies>",
            )
            pom.write_text(content, encoding="utf-8")
            with self.assertRaisesRegex(check_architecture.CheckError, "依赖方向违规"):
                check_architecture.check_repository(root)

    def test_rejects_forbidden_ddd_import(self):
        """确认领域层不能直接导入基础设施层类型。"""
        with tempfile.TemporaryDirectory() as directory:
            root = make_architecture_root(Path(directory))
            source = root / "xjar-plus-core" / "src" / "main" / "java" / "sample" / "Domain.java"
            source.write_text(
                f"package {GROUP_ID}.domain;\nimport {GROUP_ID}.infrastructure.Adapter;\nclass Domain {{}}\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(check_architecture.CheckError, "DDD 包导入方向违规"):
                check_architecture.check_repository(root)

    def test_rejects_external_entity_declarations(self):
        """确认解析 POM 前拒绝 DTD 和实体声明，不从 XML 输入加载外部资源。"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pom.xml"
            path.write_text("<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///secret'>]><project />", encoding="utf-8")
            with self.assertRaisesRegex(check_architecture.CheckError, "DTD 或实体"):
                check_architecture.read_safe_xml(path)


class GoFormatCheckTests(unittest.TestCase):
    """覆盖 Go 文件筛选、只读格式调用和格式漂移失败。"""

    def make_launcher(self, root: Path) -> Path:
        """构造包含生产、测试、vendored 和生成文件的 launcher 目录。"""
        write_text(root / "go.mod", "module example.invalid/launcher\n")
        write_text(root / "cmd" / "main.go", "package main\n")
        write_text(root / "internal" / "run_test.go", "package internal\n")
        write_text(root / "vendor" / "dep" / "vendor.go", "package dep\n")
        write_text(root / "target" / "generated.go", "package generated\n")
        return root

    def test_lists_only_maintained_production_go_files(self):
        """确认 gofmt 检查范围排除测试、vendored 依赖和生成目录。"""
        with tempfile.TemporaryDirectory() as directory:
            files = check_go_format.go_sources(self.make_launcher(Path(directory)))
            self.assertEqual([Path(directory) / "cmd" / "main.go"], files)

    def test_invokes_gofmt_with_argv_and_without_shell(self):
        """确认外部 gofmt 以参数数组调用且 shell=False，避免路径空格触发命令拼接。"""
        with tempfile.TemporaryDirectory(prefix="xjp check paths ") as directory:
            files = check_go_format.go_sources(self.make_launcher(Path(directory)))
            result = SimpleNamespace(returncode=0, stdout="", stderr="")
            with patch.object(check_go_format.subprocess, "run", return_value=result) as run:
                check_go_format.assert_formatted(files, "gofmt")
            self.assertEqual("gofmt", run.call_args.args[0][0])
            self.assertEqual("-l", run.call_args.args[0][1])
            self.assertIn(" ", run.call_args.args[0][2])
            self.assertFalse(run.call_args.kwargs["shell"])

    def test_rejects_gofmt_drift_without_rewriting_files(self):
        """确认 gofmt 列出的未格式化文件会失败，脚本本身不执行写回。"""
        with tempfile.TemporaryDirectory() as directory:
            files = check_go_format.go_sources(self.make_launcher(Path(directory)))
            source_before = files[0].read_bytes()
            result = SimpleNamespace(returncode=0, stdout=f"{files[0]}\n", stderr="")
            with patch.object(check_go_format.subprocess, "run", return_value=result):
                with self.assertRaisesRegex(check_go_format.CheckError, "格式不符合 gofmt"):
                    check_go_format.assert_formatted(files, "gofmt")
            self.assertEqual(source_before, files[0].read_bytes())


class HelpTests(unittest.TestCase):
    """确认每条命令都提供可读的中文帮助界面。"""

    def test_help_is_chinese(self):
        """确认脚本帮助包含中文用法和选项说明。"""
        for module in (check_source, check_architecture, check_go_format):
            output = io.StringIO()
            with contextlib.redirect_stdout(output), self.assertRaises(SystemExit) as exit_info:
                module.parse_args(["--help"])
            self.assertEqual(0, exit_info.exception.code)
            self.assertIn("用法：", output.getvalue())
            self.assertIn("选项", output.getvalue())
            self.assertIn("显示此帮助信息并退出", output.getvalue())
            self.assertNotIn("show this help message", output.getvalue())

    def test_default_paths_are_derived_from_script_location(self):
        """确认从任意当前目录启动时，三个门禁仍定位到仓库自身目录。"""
        repository_root = SCRIPTS_ROOT.parent
        self.assertEqual(repository_root, check_source.parse_args([]).root)
        self.assertEqual(repository_root, check_architecture.parse_args([]).root)
        self.assertEqual(repository_root / "launcher", check_go_format.parse_args([]).launcher_root)

if __name__ == "__main__":
    unittest.main()
