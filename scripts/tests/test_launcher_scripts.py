# @author kongweiguang
"""锁定脚本参数、安全边界与子进程调用契约，避免单测依赖真实发行凭据。"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import cross_build
import verify_launcher


class CrossBuildTests(unittest.TestCase):
    """验证交叉构建的目标限制、无覆盖语义和无 shell 调用。"""

    def test_supported_targets_are_explicit(self) -> None:
        """仅接受发布矩阵中的六种组合，避免成功构建被误读为兼容承诺。"""
        for target in sorted(cross_build.SUPPORTED_TARGETS):
            with self.subTest(target=target):
                self.assertEqual(2, len(cross_build.parse_target(target)))
        with self.assertRaises(argparse.ArgumentTypeError):
            cross_build.parse_target("freebsd-amd64")

    def test_existing_output_is_never_overwritten(self) -> None:
        """先拒绝已占用路径，保护调用方原有二进制而不尝试降级覆盖。"""
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "launcher"
            output.write_bytes(b"existing")
            with patch.object(cross_build, "require_go_124", return_value="go version go1.24.3"):
                with self.assertRaises(cross_build.CrossBuildError):
                    cross_build.build("windows-amd64", "go", output)
            self.assertEqual(b"existing", output.read_bytes())

    def test_build_uses_argv_environment_and_cleans_failed_output(self) -> None:
        """显式目标环境与 argv 防止 shell 注入，失败只清除本次暂存二进制。"""
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "nested" / "launcher.exe"

            def fake_run(command: list[str], **kwargs: object) -> subprocess.CompletedProcess[str]:
                if command[1] == "build":
                    Path(command[command.index("-o") + 1]).write_bytes(b"partial")
                    return subprocess.CompletedProcess(command, 2, "compile failed", None)
                return subprocess.CompletedProcess(command, 0, "go version go1.24.3 windows/amd64", None)

            with patch.object(cross_build, "resolve_executable", return_value="C:/Go/bin/go.exe"), \
                    patch.object(cross_build, "require_go_124", return_value="go version go1.24.3"), \
                     patch.object(cross_build, "run_process", side_effect=fake_run) as run:
                with self.assertRaisesRegex(cross_build.CrossBuildError, "交叉编译失败"):
                    cross_build.build("windows-amd64", "go", output)

            self.assertFalse(output.exists())
            command = run.call_args.args[0]
            options = run.call_args.kwargs
            self.assertIn("./cmd/launcher", command)
            self.assertEqual(cross_build.LAUNCHER_ROOT, options["cwd"])
            self.assertEqual(600, options["timeout_seconds"])
            self.assertEqual("windows", options["env"]["GOOS"])
            self.assertEqual("amd64", options["env"]["GOARCH"])
            self.assertEqual("0", options["env"]["CGO_ENABLED"])


class LauncherVerificationTests(unittest.TestCase):
    """验证真实验收入口的宿主约束与超时参数默认值。"""

    def test_native_target_and_target_parser(self) -> None:
        """验证目标解析只接受可运行的本机平台组合，避免误跑交叉产物。"""
        self.assertEqual(2, len(verify_launcher.parse_target("linux-amd64")))
        with self.assertRaises(argparse.ArgumentTypeError):
            verify_launcher.parse_target("linux-x86")

    def test_parser_keeps_short_expiry_window_configurable(self) -> None:
        """让验收窗口在打包完成后可控签发，避免为固定长授权无谓等待。"""
        arguments = verify_launcher.argument_parser().parse_args(
            ["--target", "windows-amd64", "--license-seconds", "35"]
        )
        self.assertEqual(35, arguments.license_seconds)
        self.assertGreaterEqual(arguments.timeout_seconds, 1)

    def test_run_root_is_confined_to_integration_target(self) -> None:
        """只允许清理脚本自己创建的集成测试临时目录，不触及仓库其他输出。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "integration-tests" / "target"
            root.mkdir(parents=True)
            run_root = root / "launcher-verification-test"
            run_root.mkdir()
            self.assertEqual(run_root.resolve(), verify_launcher.validate_run_root(root, run_root))
            with self.assertRaises(verify_launcher.VerificationError):
                verify_launcher.validate_run_root(root, Path(directory) / "other")

    def test_cli_arguments_remain_separate_and_use_utf8_process_output(self) -> None:
        """保留含中文空格的业务参数为单独 argv 项，并固定 CLI 输出解码为 UTF-8。"""
        completed = subprocess.CompletedProcess(["java"], 0, "结果✓", "诊断✓")
        with patch.object(verify_launcher, "run_checked", return_value=completed) as run:
            result = verify_launcher.invoke_cli(
                Path("java"),
                Path("tool.jar"),
                ["package", "--app-arg=标签 中文 参数"],
                30,
            )
        self.assertEqual("结果✓", result)
        self.assertEqual(
            ["java", "-Dfile.encoding=UTF-8", "-jar", "tool.jar", "package", "--app-arg=标签 中文 参数"],
            run.call_args.args[0],
        )

    def test_cli_runner_keeps_utf8_stdout_and_stderr_separate(self) -> None:
        """CLI 机器结果与进度诊断分流，避免合并流污染末尾 JSON。"""
        process = Mock()
        process.communicate.return_value = ("结果✓".encode(), "进度✓".encode())
        process.returncode = 0
        with patch.object(verify_launcher.subprocess, "Popen", return_value=process) as popen:
            result = verify_launcher.run_checked(["java", "-version"], 7, "Java 检查")
        self.assertEqual("结果✓", result.stdout)
        self.assertEqual("进度✓", result.stderr)
        self.assertEqual(7, process.communicate.call_args.kwargs["timeout"])
        self.assertFalse(popen.call_args.kwargs["shell"])

    def test_cli_runner_timeout_never_waits_unbounded_for_inherited_pipes(self) -> None:
        """命令超时后只做有界管道回收，再关闭句柄并报告，避免孙进程拖死脚本。"""
        process = Mock()
        process.communicate.side_effect = [
            subprocess.TimeoutExpired(["java"], 7),
            subprocess.TimeoutExpired(["java"], 10),
        ]
        process.poll.return_value = 0
        with patch.object(verify_launcher.subprocess, "Popen", return_value=process), \
                patch.object(verify_launcher, "terminate_owned_tree") as terminate:
            with self.assertRaisesRegex(verify_launcher.VerificationError, "输出管道仍未关闭"):
                verify_launcher.run_checked(["java"], 7, "Java 检查")
        self.assertEqual([7, 10], [call.kwargs["timeout"] for call in process.communicate.call_args_list])
        self.assertEqual(2, terminate.call_count)
        process.stdout.close.assert_called_once_with()
        process.stderr.close.assert_called_once_with()

    def test_cli_runner_cancellation_terminates_owned_tree(self) -> None:
        """Ctrl+C 时先终止本次命令树并有限等待，再把取消信号交给外层闭环。"""
        process = Mock()
        process.communicate.side_effect = [KeyboardInterrupt(), (b"", b"")]
        process.poll.return_value = 0
        with patch.object(verify_launcher.subprocess, "Popen", return_value=process), \
                patch.object(verify_launcher, "terminate_owned_tree") as terminate:
            with self.assertRaises(KeyboardInterrupt):
                verify_launcher.run_checked(["java"], 7, "Java 检查")
        terminate.assert_called_once_with(process)

    def test_cli_runner_reports_nonzero_exit_with_utf8_diagnostics(self) -> None:
        """命令非零退出保持失败，并将 stdout 与 stderr 合并到可诊断错误。"""
        process = Mock()
        process.communicate.return_value = ("失败✓".encode(), "错误✓".encode())
        process.returncode = 7
        with patch.object(verify_launcher.subprocess, "Popen", return_value=process):
            with self.assertRaisesRegex(verify_launcher.VerificationError, "exit=7.*失败✓错误✓"):
                verify_launcher.run_checked(["java"], 7, "Java 检查")

    def test_tree_cleanup_runs_even_after_root_process_exits(self) -> None:
        """根 PID 结束不证明独立进程组中的后代已结束，因此仍调用组清理器。"""
        process = Mock()
        process.pid = 1234
        process.poll.return_value = 0
        with patch.object(verify_launcher, "stop_process_tree") as stop_tree:
            verify_launcher.terminate_owned_tree(process)
        stop_tree.assert_called_once_with(process)

    def test_launcher_is_registered_before_readiness_check(self) -> None:
        """启动失败或取消发生在端口就绪之前时，外层 finally 仍能回收该进程。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            process = Mock()
            process.pid = 4321
            process.poll.return_value = 17
            process.returncode = 17
            active: list[verify_launcher.LauncherProcess] = []
            log_path = root / "launcher.log"
            with patch.object(verify_launcher.subprocess, "Popen", return_value=process), \
                    patch.object(verify_launcher, "launcher_environment", return_value={}), \
                    patch.object(verify_launcher, "read_log", return_value="startup failed"):
                with self.assertRaisesRegex(verify_launcher.VerificationError, "提前退出"):
                    verify_launcher.start_launcher(
                        root / "launcher.exe", root, root / "java.exe", root, log_path, active
                    )
            self.assertEqual(1, len(active))
            self.assertIs(process, active[0].process)

    def test_cancelled_acceptance_returns_interrupt_exit_code(self) -> None:
        """Ctrl+C 在 run_acceptance 的 finally 清理后以稳定取消码退出。"""
        with patch.object(verify_launcher, "run_acceptance", side_effect=KeyboardInterrupt):
            self.assertEqual(130, verify_launcher.main([]))

    def test_external_java_overrides_inherited_java_home(self) -> None:
        """外部 Java 启动必须优先使用验收指定的 JDK，而不是宿主遗留 JAVA_HOME。"""
        java = Path("/selected/jdk/bin/java")
        temp_directory = Path("/private/temp")
        with patch.dict(verify_launcher.os.environ, {"JAVA_HOME": "old-jdk", "PATH": "old-path"}, clear=True):
            environment = verify_launcher.launcher_environment(
                java, temp_directory
            )
        self.assertEqual(str(java.parent.parent), environment["JAVA_HOME"])
        self.assertEqual(f"{java.parent}{verify_launcher.os.pathsep}old-path", environment["PATH"])
        self.assertEqual("0", environment["XJAR_PLUS_STATUS_PORT"])
        self.assertEqual(str(temp_directory), environment["TMPDIR"])

    def test_build_result_uses_cli_output_path(self) -> None:
        """只信任 CLI 返回的产物路径，避免用 appId 推断可执行文件名。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            executable = root / "app-hash" / "launcher.exe"
            executable.parent.mkdir()
            executable.write_bytes(b"launcher")
            result = verify_launcher.parse_build_result(
                json.dumps({"output": str(executable)}), root
            )
            self.assertEqual(executable.resolve(), result)

    def test_iso_instant_is_normalized_to_utc_seconds(self) -> None:
        """签发协议使用 UTC 整秒，避免本机时区和亚秒精度改变许可证边界。"""
        value = verify_launcher.datetime(2026, 10, 23, 12, 0, 0, 500000,
                                         tzinfo=verify_launcher.timezone(verify_launcher.timedelta(hours=8)))
        self.assertEqual("2026-10-23T04:00:00Z", verify_launcher.instant_text(value))


if __name__ == "__main__":
    unittest.main()
