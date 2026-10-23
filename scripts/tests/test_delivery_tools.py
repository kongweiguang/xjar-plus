# @author kongweiguang
"""验证交付脚本的版本、矩阵选择、暂存安全和哈希边界。"""

from __future__ import annotations

import contextlib
import io
import os
import subprocess
import sys
import tempfile
import time
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


SCRIPTS_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_ROOT))

import release
import verify_compatibility
from _delivery_common import CommandTimeoutError, command_argv, run_checked, run_process, windows_batch_command


class CompatibilityScriptTests(unittest.TestCase):
    """覆盖兼容性矩阵筛选、Java 版本识别和临时 POM 改写。"""

    def assert_default_maven_namespace(self, pom: Path) -> str:
        """确认临时 POM 使用默认 Maven 命名空间并保留 XSI schema 与 XML 声明。"""
        serialized = pom.read_text(encoding="utf-8")
        declaration = serialized.splitlines()[0]
        self.assertTrue(declaration.startswith("<?xml version="))
        self.assertIn("encoding=", declaration)
        self.assertTrue(declaration.endswith("?>"))
        root_element = serialized.splitlines()[1].lstrip()
        self.assertTrue(root_element.startswith("<project "))
        self.assertIn('xmlns="http://maven.apache.org/POM/4.0.0"', serialized)
        self.assertIn('xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"', serialized)
        self.assertIn("xsi:schemaLocation=", serialized)
        self.assertIn("<modelVersion>4.0.0</modelVersion>", serialized)
        self.assertNotIn("ns0:", serialized)
        return serialized

    def test_selects_only_requested_boot_line_and_matching_layout(self):
        """只选择指定 Boot minor，并保留同版本布局变体，不误带其它 fixture。"""
        matrix = {
            "springBoot": [{"version": "3.2.12"}, {"version": "3.3.13"}],
            "solon": [{"version": "4.1.1"}],
            "layoutVariants": [{"framework": "springBoot", "version": "3.2.12", "loaderImplementation": "CLASSIC"}],
        }
        boot, solon, variants = verify_compatibility.select_matrix_entries(matrix, "boot", "3.2.12")
        self.assertEqual(["3.2.12"], [item["version"] for item in boot])
        self.assertEqual([], solon)
        self.assertEqual(1, len(variants))

    def test_rejects_boot_version_outside_boot_framework(self):
        """Boot 专属版本选择不能与 plain 模式混用，避免表面成功但未测指定版本。"""
        with self.assertRaisesRegex(verify_compatibility.CompatibilityError, "只能与"):
            verify_compatibility.select_matrix_entries({"springBoot": []}, "plain", "3.2.12")

    def test_pins_only_the_named_parent_version(self):
        """临时 POM 只改 parent 版本并保持 Maven 可识别的默认命名空间。"""
        source = '''<?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <parent><groupId>org.example</groupId><artifactId>framework-parent</artifactId><version>1.0</version></parent>
          <artifactId>fixture</artifactId><version>2.0</version>
        </project>'''
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / "pom.xml"
            pom.write_text(source, encoding="utf-8")
            generated = verify_compatibility.pin_parent_version(pom, "framework-parent", "3.2.12")
            try:
                self.assert_default_maven_namespace(generated)
                tree = verify_compatibility.safe_parse_xml(generated)
                namespace = "{http://maven.apache.org/POM/4.0.0}"
                self.assertEqual("3.2.12", tree.getroot().findtext(f"{namespace}parent/{namespace}version"))
                self.assertEqual("2.0", tree.getroot().findtext(f"{namespace}version"))
                self.assertIn('version>1.0<', pom.read_text(encoding="utf-8"))
            finally:
                generated.unlink(missing_ok=True)

    def test_configures_loader_only_in_transient_pom(self):
        """CLASSIC 变体写回仍保留默认 Maven 命名空间，不修改仓库生产 fixture。"""
        source = '''<?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0"
                 xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
          <modelVersion>4.0.0</modelVersion>
          <build><plugins><plugin><groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
        </project>'''
        with tempfile.TemporaryDirectory() as directory:
            pom = Path(directory) / "pom.xml"
            pom.write_text(source, encoding="utf-8")
            verify_compatibility.configure_boot_loader(pom, "CLASSIC")
            self.assert_default_maven_namespace(pom)
            tree = verify_compatibility.safe_parse_xml(pom)
            namespace = "{http://maven.apache.org/POM/4.0.0}"
            self.assertEqual(
                "CLASSIC",
                tree.getroot().findtext(
                    f"{namespace}build/{namespace}plugins/{namespace}plugin/{namespace}configuration/{namespace}loaderImplementation"
                ),
            )

    def test_detects_major_version_forms_for_java_8_and_modern_java(self):
        """解析 JDK 8 的 1.8 写法和现代 JDK major，避免次版本号误作 major。"""
        self.assertEqual("8", verify_compatibility.JAVA_VERSION.search('java version "1.8.0_452"').group(1))
        self.assertEqual("21", verify_compatibility.JAVA_VERSION.search('openjdk version "21.0.7"').group(1))

    def test_java_version_probe_uses_a_bounded_process(self):
        """JDK 探测使用规范化路径并限制等待时间，兼容临时目录别名且避免流程挂起。"""
        with tempfile.TemporaryDirectory() as directory:
            java = Path(directory) / "bin" / "java.exe"
            java.parent.mkdir()
            java.write_bytes(b"fixture")
            completed = subprocess.CompletedProcess([str(java), "-version"], 0, 'openjdk version "21.0.7"', None)
            with patch.object(verify_compatibility, "run_process", return_value=completed) as runner:
                executable, home = verify_compatibility.resolve_java(str(java), 21)
            resolved_java = java.resolve()
            self.assertEqual(str(resolved_java), executable)
            self.assertEqual(str(java.parent.parent.resolve()), str(home))
            runner.assert_called_once_with(
                [str(resolved_java), "-version"], cwd=resolved_java.parent, timeout_seconds=20
            )

    def test_maven_fixture_commands_have_a_long_but_finite_deadline(self):
        """矩阵 Maven 单项允许依赖下载和编译，但每次调用都有有限上限。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            java_home = root / "jdk"
            with patch.object(verify_compatibility, "run_checked") as runner:
                verify_compatibility.run_maven("mvn", ["-B", "package"], root, java_home)
            self.assertEqual(900, runner.call_args.kwargs["timeout_seconds"])
            self.assertTrue(runner.call_args.kwargs["stream"])
            self.assertEqual(str(java_home), runner.call_args.kwargs["env"]["JAVA_HOME"])


class ReleaseScriptTests(unittest.TestCase):
    """覆盖发布身份、附件拒绝规则、清单读回和用户说明。"""

    def test_validates_project_coordinate_without_accepting_other_versions(self):
        """tag 参数必须与源 POM 的 groupId/version 完全一致。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_text(
                f'<project><groupId>{release.GROUP_ID}</groupId><version>2.0.0</version></project>',
                encoding="utf-8",
            )
            self.assertEqual("2.0.0", release.validate_project_identity(root, "v2.0.0"))
            with self.assertRaisesRegex(release.ReleaseError, "必须均为"):
                release.validate_project_identity(root, "v2.0.1")

    def test_rejects_dtd_in_project_pom(self):
        """外部实体不能参与版本识别或访问本地敏感文件。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "pom.xml").write_text(
                '<!DOCTYPE project [<!ENTITY local SYSTEM "file:///secret">]><project/>', encoding="utf-8"
            )
            with self.assertRaisesRegex(release.ReleaseError, "DTD"):
                release.read_project_identity(root / "pom.xml")

    def test_rejects_sensitive_archive_paths_but_not_issuer_class_names(self):
        """安全扫描只拒絕敏感產物路徑，不會误判 SDK 的 IssuerCredentials 类型。"""
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory)
            sdk = stage / "sdk.zip"
            with zipfile.ZipFile(sdk, "w") as archive:
                archive.writestr("repository/IssuerCredentials.class", b"class")
            release.assert_public_artifacts(stage)
            with zipfile.ZipFile(sdk, "a") as archive:
                archive.writestr("private/demo.issuer", b"secret")
            with self.assertRaisesRegex(release.ReleaseError, "禁止路径"):
                release.assert_public_artifacts(stage)

    def test_writes_and_reads_back_exact_seven_asset_manifest(self):
        """清单应包含七个内容资产并逐一核对，最终再增加自身成为八文件集合。"""
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory)
            names = (
                "xjar-plus-2.0.0.jar",
                "xjar-plus-2.0.0-compatibility-matrix.json",
                "COMPATIBILITY.md",
                "xjar-plus-2.0.0-skill.zip",
                "xjar-plus-2.0.0-sdk.zip",
                "xjar-plus-2.0.0-java.cdx.json",
                "xjar-plus-2.0.0-go.cdx.json",
            )
            for name in names:
                (stage / name).write_bytes(name.encode("utf-8"))
            release.write_and_verify_hashes(stage, "2.0.0")
            self.assertEqual(8, len(list(stage.iterdir())))
            for line in (stage / "SHA256SUMS").read_text(encoding="ascii").splitlines():
                digest, name = line.split("  ", maxsplit=1)
                self.assertEqual(digest, release.sha256(stage / name))

    def test_rejects_a_changed_on_disk_checksum_manifest(self):
        """回读真实 SHA256SUMS，而非只验证生成它的内存行。"""
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory)
            names = (
                "xjar-plus-2.0.0.jar",
                "xjar-plus-2.0.0-compatibility-matrix.json",
                "COMPATIBILITY.md",
                "xjar-plus-2.0.0-skill.zip",
                "xjar-plus-2.0.0-sdk.zip",
                "xjar-plus-2.0.0-java.cdx.json",
                "xjar-plus-2.0.0-go.cdx.json",
            )
            for name in names:
                (stage / name).write_bytes(name.encode("utf-8"))
            original_read_text = Path.read_text

            def alter_manifest(path, *args, **kwargs):
                """仅篡改清单的首个摘要，其他文件读取仍走真实磁盘内容。"""
                content = original_read_text(path, *args, **kwargs)
                if path.name == "SHA256SUMS":
                    first, *remaining = content.splitlines()
                    digest, filename = first.split("  ", maxsplit=1)
                    replacement = ("0" if digest[0] != "0" else "1") + digest[1:]
                    return "\n".join([f"{replacement}  {filename}", *remaining]) + "\n"
                return content

            with patch.object(Path, "read_text", alter_manifest):
                with self.assertRaisesRegex(release.ReleaseError, "SHA-256 回读失败"):
                    release.write_and_verify_hashes(stage, "2.0.0")

    def test_sdk_readme_points_to_python_installer_and_four_examples(self):
        """SDK 交付说明必须与 Python 安装器和四个 main 示例相符。"""
        readme = release.sdk_readme("2.0.0")
        self.assertIn("python ./install_sdk.py", readme)
        self.assertIn("此压缩包包含四个 Java 库", readme)
        self.assertIn("请在当前目录使用 JDK 21", readme)
        self.assertEqual(4, readme.count("java -cp xjar-plus-2.0.0.jar examples/"))
        self.assertNotIn("install-sdk.ps1", readme)

    def test_generated_compatibility_note_is_concise_chinese_and_evidence_scoped(self):
        """发布随附兼容说明使用中文且明确区分本地证据与 tag CI 结果。"""
        self.assertIn("# XJar Plus 兼容性说明", release.COMPATIBILITY_NOTE)
        self.assertIn("本地验证不能证明 tag CI 已通过", release.COMPATIBILITY_NOTE)
        self.assertNotIn("verified for this release", release.COMPATIBILITY_NOTE)

    def test_sdk_archive_has_flat_root_and_self_contained_python_installer(self):
        """SDK ZIP 保持根级 README/repository/examples 布局，安装脚本不依赖仓库内部模块。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            sdk_root = root / "stage" / ".sdk-work"
            sdk_root.parent.mkdir()
            (root / "pom.xml").write_text("<project/>", encoding="utf-8")
            for filename in ("LICENSE", "NOTICE"):
                (root / filename).write_text(filename, encoding="utf-8")
            for module in release.SDK_MODULES:
                module_root = root / module
                target = module_root / "target"
                target.mkdir(parents=True)
                (module_root / "pom.xml").write_text("<project/>", encoding="utf-8")
                (target / f"{module}-2.0.0.jar").write_bytes(b"jar")
                (target / f"{module}-2.0.0-sources.jar").write_bytes(b"sources")
            examples = root / "examples"
            examples.mkdir()
            for filename in release.SDK_EXAMPLES:
                (examples / filename).write_text("example", encoding="utf-8")
            release_jar = root / "cli.jar"
            release_jar.write_bytes(b"cli")
            archive_path = release.create_sdk_archive(root, sdk_root, "2.0.0", release_jar)
            with zipfile.ZipFile(archive_path) as archive:
                names = set(archive.namelist())
                self.assertIn("README.md", names)
                self.assertIn("install_sdk.py", names)
                self.assertIn("repository/xjar-plus-core-2.0.0.jar", names)
                self.assertNotIn(".sdk-work/README.md", names)
                installer = archive.read("install_sdk.py").decode("utf-8")
                compile(installer, "install_sdk.py", "exec")
            installer_path = root / "install_sdk.py"
            installer_path.write_text(installer, encoding="utf-8")
            help_result = subprocess.run(
                [sys.executable, str(installer_path), "--help"],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                env={**os.environ, "PYTHONIOENCODING": "utf-8"},
                shell=False,
            )
            self.assertEqual(0, help_result.returncode, help_result.stderr)
            self.assertIn("用法：", help_result.stdout)
            self.assertIn("显示此帮助信息", help_result.stdout)


class HelpTests(unittest.TestCase):
    """确认新交付命令的终端帮助面向中文用户。"""

    def test_help_is_chinese(self):
        """release 和 compatibility 命令通过标准 argparse 输出中文标题。"""
        for module in (release, verify_compatibility):
            output = io.StringIO()
            with contextlib.redirect_stdout(output), self.assertRaises(SystemExit) as exit_info:
                module.parse_args(["--help"])
            self.assertEqual(0, exit_info.exception.code)
            self.assertIn("用法：", output.getvalue())
            self.assertIn("选项", output.getvalue())
            self.assertIn("显示此帮助信息", output.getvalue())
            self.assertNotIn("show this help message", output.getvalue())


class CommandAdapterTests(unittest.TestCase):
    """覆盖 Windows Maven 批处理入口由 Python shell 转义的参数契约。"""

    def test_windows_batch_command_keeps_argv_and_rejects_percent_expansion(self):
        """批处理文件必须启用 Python shell 转义，且拒绝 cmd 百分号变量展开。"""
        argv = command_argv(r"C:\Program Files\Maven\mvn.cmd", ["-B", r"-Ddir=C:\work tree"], windows=True)
        self.assertEqual([r"C:\Program Files\Maven\mvn.cmd", "-B", r"-Ddir=C:\work tree"], argv)
        self.assertTrue(windows_batch_command(r"C:\Program Files\Maven\mvn.cmd", windows=True))
        with self.assertRaisesRegex(ValueError, "%"):
            command_argv("mvn.cmd", ["-Ddir=C:\\release%path"], windows=True)

    def test_native_executables_keep_argument_array(self):
        """非批处理平台工具仍以原始 argv 调用，避免不必要地进入 cmd。"""
        self.assertEqual(["java", "-version"], command_argv("java", ["-version"], windows=True))
        self.assertFalse(windows_batch_command("java.exe", windows=True))

    def test_command_timeout_reaps_only_its_process_tree(self):
        """超时错误保留诊断，并确认直属进程及其子进程均已被回收。"""
        child_code = "import time; time.sleep(60)"
        parent_code = (
            "import subprocess,sys,time; child=subprocess.Popen([sys.executable,'-c',sys.argv[1]]); "
            "print(child.pid,flush=True); time.sleep(60)"
        )
        with self.assertRaises(CommandTimeoutError) as timeout:
            run_process(
                [sys.executable, "-c", parent_code, child_code],
                cwd=Path.cwd(),
                timeout_seconds=1,
            )
        child_pid = int(timeout.exception.output.strip().splitlines()[0])
        for _ in range(40):
            try:
                os.kill(child_pid, 0)
            except OSError:
                break
            if os.name != "nt":
                stat_file = Path(f"/proc/{child_pid}/stat")
                if stat_file.is_file() and stat_file.read_text(encoding="ascii").split()[2] == "Z":
                    break
            time.sleep(0.1)
        else:
            self.fail(f"超时后子进程仍存活：{child_pid}")

    def test_keyboard_interrupt_reaps_the_owned_process_tree(self):
        """Ctrl+C 清理当前命令组后仍向调用者传播中断，不留下长驻子进程。"""
        with tempfile.TemporaryDirectory() as directory:
            child_pid_file = Path(directory) / "child.pid"
            parent_code = (
                "import subprocess,sys,time; child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(60)']); "
                f"open({str(child_pid_file)!r},'w',encoding='ascii').write(str(child.pid)); time.sleep(60)"
            )
            arguments = [sys.executable, "-c", parent_code]
            original_communicate = subprocess.Popen.communicate
            interrupted = False

            def interrupt_parent_wait(process, *args, **kwargs):
                """模拟父命令收到 Ctrl+C，同时保留 taskkill 子命令的正常 communicate。"""
                nonlocal interrupted
                if process.args == arguments and not interrupted:
                    interrupted = True
                    time.sleep(0.5)
                    raise KeyboardInterrupt
                return original_communicate(process, *args, **kwargs)

            with patch.object(subprocess.Popen, "communicate", interrupt_parent_wait):
                with self.assertRaises(KeyboardInterrupt):
                    run_process(arguments, cwd=Path(directory), timeout_seconds=30)
            child_pid = int(child_pid_file.read_text(encoding="ascii"))
            for _ in range(40):
                try:
                    os.kill(child_pid, 0)
                except OSError:
                    break
                if os.name != "nt":
                    stat_file = Path(f"/proc/{child_pid}/stat")
                    if stat_file.is_file() and stat_file.read_text(encoding="ascii").split()[2] == "Z":
                        break
                time.sleep(0.1)
            else:
                self.fail(f"Ctrl+C 后子进程仍存活：{child_pid}")

    @unittest.skipUnless(os.name == "nt", "批处理参数执行仅能在原生 Windows 验证。")
    def test_windows_batch_metacharacters_remain_inside_the_argument(self):
        """真实 cmd 执行中 & 只能作为参数文本，不能拆出另一条命令。"""
        with tempfile.TemporaryDirectory(prefix="xjp cmd ") as directory:
            script = Path(directory) / "fake maven.cmd"
            script.write_text("@echo off\necho FIRST=[%1]\necho SECOND=[%2]\n", encoding="utf-8")
            result = run_checked(str(script), ["-B", "literal&echo INJECTED"], cwd=Path(directory))
            self.assertEqual(
                ['FIRST=[-B]', 'SECOND=["literal&echo INJECTED"]'],
                result.stdout.splitlines(),
            )

    @unittest.skipUnless(os.name == "nt", "SDK 批处理安装器仅能在原生 Windows 验证。")
    def test_generated_sdk_installer_runs_fake_maven_batch(self):
        """打包内嵌安装器在路径含空格时也能安全多次调用 Windows Maven 批处理入口。"""
        with tempfile.TemporaryDirectory(prefix="xjp sdk ") as directory:
            sdk_root = Path(directory)
            repository = sdk_root / "repository"
            repository.mkdir()
            version = "2.0.0"
            (repository / f"xjar-plus-parent-{version}.pom").write_text("<project/>", encoding="utf-8")
            for artifact in release.SDK_MODULES:
                filenames = (
                    f"{artifact}-{version}.pom",
                    f"{artifact}-{version}.jar",
                    f"{artifact}-{version}-sources.jar",
                )
                for filename in filenames:
                    (repository / filename).write_bytes(b"fixture")
            installer = sdk_root / "install_sdk.py"
            installer.write_text(release.SDK_INSTALLER_TEMPLATE.replace("__VERSION__", version), encoding="utf-8")
            fake_maven = sdk_root / "fake maven.cmd"
            argument_log = sdk_root / "maven arguments.txt"
            fake_maven.write_text(f'@echo off\necho %*>>"{argument_log}"\nexit /b 0\n', encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(installer), "--maven-command", str(fake_maven)],
                cwd=sdk_root,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                env={**os.environ, "PYTHONIOENCODING": "utf-8"},
                shell=False,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            invocations = argument_log.read_text(encoding="utf-8").splitlines()
            self.assertEqual(5, len(invocations))
            self.assertTrue(all("org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file" in line
                                for line in invocations))
            self.assertIn("xjar-plus-parent-2.0.0.pom", invocations[0])
            self.assertEqual(4, sum("-Dsources=" in line for line in invocations))

    @unittest.skipUnless(os.name == "nt", "SDK Maven 批处理超时回收需在原生 Windows 验证。")
    def test_generated_sdk_installer_bounds_and_reaps_slow_maven_tree(self):
        """SDK 自包含安装器超时后通过 taskkill /T 回收 Maven 子树并返回失败。"""
        with tempfile.TemporaryDirectory(prefix="xjp sdk timeout ") as directory:
            sdk_root = Path(directory)
            repository = sdk_root / "repository"
            repository.mkdir()
            (repository / "xjar-plus-parent-2.0.0.pom").write_text("<project/>", encoding="utf-8")
            slow_script = sdk_root / "slow_maven.py"
            slow_script.write_text(
                "import subprocess, sys, time\n"
                "child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])\n"
                "print(child.pid, flush=True)\n"
                "time.sleep(60)\n",
                encoding="utf-8",
            )
            installer = sdk_root / "install_sdk.py"
            installer_source = release.SDK_INSTALLER_TEMPLATE.replace("__VERSION__", "2.0.0")
            installer_source = installer_source.replace("MAVEN_TIMEOUT_SECONDS = 180", "MAVEN_TIMEOUT_SECONDS = 0.2")
            installer.write_text(installer_source, encoding="utf-8")
            fake_maven = sdk_root / "slow maven.cmd"
            fake_maven.write_text(f'@echo off\n"{sys.executable}" "{slow_script}"\n', encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(installer), "--maven-command", str(fake_maven)],
                cwd=sdk_root,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                env={**os.environ, "PYTHONIOENCODING": "utf-8"},
                shell=False,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("超过 0.2 秒，已回收本次进程树", result.stderr)
            child_pid = int(result.stdout.strip().splitlines()[0])
            for _ in range(40):
                try:
                    os.kill(child_pid, 0)
                except OSError:
                    break
                time.sleep(0.1)
            else:
                self.fail(f"SDK 安装器超时后 Maven 子进程仍存活：{child_pid}")


if __name__ == "__main__":
    unittest.main()
