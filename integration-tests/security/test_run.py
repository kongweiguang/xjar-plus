# @author kongweiguang
"""固定 Python 运行器的机器输出与失败边界，不依赖 Java、Go 或真实凭据。"""

from contextlib import redirect_stderr
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

import run as runner


class RunnerTest(unittest.TestCase):
    """只启动本次测试拥有的短命 Python 子进程，不操作用户进程。"""

    def test_utf8_and_legacy_jdk_output(self) -> None:
        """统一输出 UTF-8，但仍能读取旧 JDK 使用本地代码页生成的诊断。"""
        expected = "中文路径"
        self.assertEqual(runner.decode_output(expected.encode("utf-8")), expected)
        with patch.object(runner.locale, "getpreferredencoding", return_value="cp936"):
            self.assertEqual(runner.decode_output(expected.encode("cp936")), expected)

    def test_windows_utf16_output(self) -> None:
        """WSL 无 BOM 的 UTF-16 提示不能变成乱码或混入机器输出。"""
        expected = "wsl: localhost 代理配置提示\r\n"
        for encoding in ("utf-16", "utf-16-le", "utf-16-be"):
            with self.subTest(encoding=encoding):
                value = expected.encode(encoding)
                if encoding == "utf-16-be":
                    value = b"\xfe\xff" + value
                self.assertEqual(runner.decode_output(value), expected)

    def test_machine_output_excludes_diagnostic(self) -> None:
        """正常启动警告必须保留给人看，但不能影响 uname 或 wslpath 的解析。"""
        program = ("import sys; sys.stdout.buffer.write(b'x86_64\\n'); "
                   "sys.stderr.buffer.write('wsl: localhost 代理提示'.encode('utf-16-le'))")
        diagnostic = io.StringIO()
        with redirect_stderr(diagnostic):
            actual = runner.run_checked(sys.executable, ["-c", program], Path(__file__).parent,
                                        5, capture=True, stdout_only=True)
        self.assertEqual(actual, "x86_64\n")
        self.assertIn("代理提示", diagnostic.getvalue())

    def test_unicode_arguments_with_spaces(self) -> None:
        """保持 argv 边界，中文、空格和 emoji 不应经过 shell 再次解释。"""
        expected = "中文目录 空格 🔒"
        program = "import sys; sys.stdout.buffer.write(sys.argv[1].encode('utf-8'))"
        actual = runner.run_checked(sys.executable, ["-c", program, expected], Path(__file__).parent,
                                    5, capture=True)
        self.assertEqual(actual, expected)

    def test_nonzero_exit_is_failure(self) -> None:
        """非零退出不能被后续成功覆盖。"""
        with self.assertRaisesRegex(RuntimeError, "退出码：7"):
            runner.run_checked(sys.executable, ["-c", "raise SystemExit(7)"], Path(__file__).parent,
                               5, capture=True)

    def test_command_timeout_is_failure(self) -> None:
        """测试辅助进程自带短兜底，超时仍必须由运行器提前回收并报错。"""
        with self.assertRaisesRegex(RuntimeError, "超过 1 秒"):
            runner.run_checked(sys.executable, ["-c", "import time; time.sleep(3)"], Path(__file__).parent,
                               1, capture=True)

    def test_invalid_timeout_is_argument_error(self) -> None:
        """非法期限在预检和创建 target 之前以参数错误退出。"""
        for value in ("0", "1801", "invalid"):
            with self.subTest(value=value), redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    runner.parse_args(["--command-timeout-seconds", value])
                self.assertEqual(error.exception.code, 2)


if __name__ == "__main__":
    unittest.main()
