# @author kongweiguang
"""固定 Darwin 僵尸组竞态，并确认及时回收根进程不会漏掉仍存活的后代。"""

from contextlib import contextmanager
import errno
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import _delivery_common as common


@contextmanager
def adopt_test_orphans():
    """Linux 测试进程临时接管自己的孤儿，避免 WSL 的 init 回收时机留下测试僵尸。"""
    if sys.platform != "linux":
        yield
        return
    import ctypes
    libc = ctypes.CDLL(None, use_errno=True)
    previous = ctypes.c_int()
    if libc.prctl(37, ctypes.byref(previous), 0, 0, 0) != 0 or libc.prctl(36, 1, 0, 0, 0) != 0:
        raise OSError(ctypes.get_errno(), "无法启用测试孤儿回收")
    try:
        yield
    finally:
        libc.prctl(36, previous.value, 0, 0, 0)


class ProcessGroupCleanupTests(unittest.TestCase):
    """模拟 POSIX 系统调用，因此 Windows 也能回归 Darwin 的退出边界。"""

    def test_reaps_root_before_group_probe(self):
        """Darwin 只剩直属僵尸时应先 poll 回收，不能直接探测并触发 EPERM。"""
        process = Mock(pid=1234)
        reaped = False

        def poll():
            """用可观察状态模拟 waitpid 回收，而非仅返回一个退出码。"""
            nonlocal reaped
            reaped = True
            return 0

        def killpg(pid, signum):
            """未回收时复现 Darwin 错误，回收后组才真正不存在。"""
            self.assertEqual((1234, 0), (pid, signum))
            if not reaped:
                raise PermissionError(errno.EPERM, "unreaped zombie group")
            raise ProcessLookupError(errno.ESRCH, "group gone")

        process.poll.side_effect = poll
        with patch.object(common, "os", SimpleNamespace(killpg=killpg)):
            self.assertFalse(common._signal_process_group(process, 0, 0))
        self.assertTrue(reaped)

    def test_rechecks_when_root_exits_between_poll_and_signal(self):
        """首次探测 EPERM 后再次 poll 回收刚退出的根，必须以第二次 ESRCH 确认退出。"""
        process = Mock(pid=1234)
        process.poll.side_effect = [None, 0]
        killpg = Mock(side_effect=[PermissionError(errno.EPERM, "exit race"),
                                   ProcessLookupError(errno.ESRCH, "group gone")])
        with patch.object(common, "os", SimpleNamespace(killpg=killpg)):
            self.assertFalse(common._signal_process_group(process, 0, 0))
        self.assertEqual(2, killpg.call_count)
        self.assertEqual(2, process.poll.call_count)

    def test_waits_boundedly_for_nonchild_zombie_group(self):
        """非直属僵尸不能由 poll 回收；短暂重试仍必须等到系统明确报告 ESRCH。"""
        process = Mock(pid=1234)
        process.poll.return_value = 0
        denied = PermissionError(errno.EPERM, "orphan zombie group")
        killpg = Mock(side_effect=[denied, denied, ProcessLookupError(errno.ESRCH, "group gone")])
        with patch.object(common, "os", SimpleNamespace(killpg=killpg)), \
                patch.object(common.time, "monotonic", return_value=0), \
                patch.object(common.time, "sleep") as sleep:
            self.assertFalse(common._signal_process_group(process, 0, 0.1))
        sleep.assert_called_once_with(0.05)

    def test_persistent_permission_denial_is_not_success(self):
        """父进程已退出也不能将持续权限拒绝误判为整个进程组消失。"""
        process = Mock(pid=1234)
        process.poll.return_value = 0
        denied = PermissionError(errno.EPERM, "real permission denial")
        killpg = Mock(side_effect=denied)
        with patch.object(common, "os", SimpleNamespace(killpg=killpg)), \
                patch.object(common.time, "monotonic", side_effect=[0, 0.1]), \
                patch.object(common.time, "sleep") as sleep:
            with self.assertRaises(PermissionError) as failure:
                common._signal_process_group(process, 0, 0.1)
        self.assertIs(denied, failure.exception)
        self.assertEqual(4, killpg.call_count)
        sleep.assert_called_once_with(0.05)

    def test_live_root_permission_denial_fails_immediately(self):
        """根进程尚活时没有僵尸退出证据，不能掩盖真实权限错误。"""
        process = Mock(pid=1234)
        process.poll.return_value = None
        denied = PermissionError(errno.EPERM, "real permission denial")
        killpg = Mock(side_effect=denied)
        with patch.object(common, "os", SimpleNamespace(killpg=killpg)), patch.object(common.time, "sleep") as sleep:
            with self.assertRaises(PermissionError) as failure:
                common._signal_process_group(process, 15, 2)
        self.assertIs(denied, failure.exception)
        killpg.assert_called_once_with(1234, 15)
        sleep.assert_not_called()

    def test_exited_root_does_not_skip_force_killing_remaining_group(self):
        """根已被回收仍须在宽限耗尽后向同一组发 KILL，不以 poll 的退出码提前成功。"""
        process = Mock(pid=1234)
        process.poll.return_value = 0
        killpg = Mock()
        with patch.object(common, "os", SimpleNamespace(name="posix", killpg=killpg)), \
                patch.object(common, "signal", SimpleNamespace(SIGTERM=15, SIGKILL=9)), \
                patch.object(common.time, "monotonic", side_effect=[0, 2]):
            common.stop_process_tree(process)
        self.assertEqual([(1234, 15), (1234, 0), (1234, 9)], [call.args for call in killpg.call_args_list])
        process.kill.assert_not_called()
        process.wait.assert_called_once_with(timeout=10)


@unittest.skipUnless(os.name == "posix", "真实进程组用例仅在 POSIX 执行；Windows 运行上面的模拟回归。")
class RealPosixCleanupTests(unittest.TestCase):
    """真实后代保留 stdout 且忽略 TERM，避免用根进程退出假装清理成功。"""

    def test_timeout_kills_ignored_term_descendant_after_root_exit(self):
        """根先自然退出，后代仍须由运行器在短期限内杀死，不能等辅助进程自行到期。"""
        with adopt_test_orphans(), tempfile.TemporaryDirectory(prefix="xjp-cleanup-") as directory:
            pid_file = Path(directory) / "descendant.pid"
            child = (
                "import os,signal,sys,time; signal.signal(signal.SIGTERM,signal.SIG_IGN); signal.alarm(15); "
                "print('ready',flush=True); open(sys.argv[1],'w').write(str(os.getpid())); time.sleep(15)"
            )
            parent = (
                "import pathlib,subprocess,sys,time; subprocess.Popen([sys.executable,'-c',sys.argv[1],sys.argv[2]]); "
                "deadline=time.monotonic()+5; p=pathlib.Path(sys.argv[2]); "
                "exec('while not p.exists() and time.monotonic()<deadline: time.sleep(.01)'); "
                "print('root-exited',flush=True)"
            )
            started = time.monotonic()
            try:
                with self.assertRaises(common.CommandTimeoutError) as timeout:
                    common.run_process([sys.executable, "-c", parent, child, str(pid_file)],
                                       cwd=Path(directory), timeout_seconds=1)
                self.assertIn("root-exited", timeout.exception.output)
                self.assertLess(time.monotonic() - started, 10)
                pid = int(pid_file.read_text(encoding="ascii"))
                for _ in range(40):
                    try:
                        os.waitpid(pid, os.WNOHANG)
                    except ChildProcessError:
                        pass
                    try:
                        os.kill(pid, 0)
                    except ProcessLookupError:
                        break
                    time.sleep(0.05)
                else:
                    self.fail("忽略 TERM 的后代仍在运行")
            finally:
                if pid_file.exists():
                    pid = int(pid_file.read_text(encoding="ascii"))
                    try:
                        os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    for _ in range(40):
                        try:
                            if os.waitpid(pid, os.WNOHANG)[0] == pid:
                                break
                        except ChildProcessError:
                            break
                        time.sleep(0.05)


if __name__ == "__main__":
    unittest.main()
