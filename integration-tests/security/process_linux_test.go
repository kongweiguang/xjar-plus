//go:build linux

// @author kongweiguang
package securitytests

import (
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/kongweiguang/xjar-plus/launcher/internal/process"
)

// TestUnixTreeHelper 继承测试进程组，并在清理断言之后才兜底到期，
// 避免监督器失败留下永久孤儿进程，也不掩盖清理失败。
func TestUnixTreeHelper(t *testing.T) {
	const helperLifetime = 30 * time.Second
	role := os.Getenv("XJP_SECURITY_TREE_ROLE")
	if role == "" {
		return
	}
	if role == "grandchild" {
		time.Sleep(helperLifetime)
		return
	}
	child := exec.Command(os.Args[0], "-test.run=^TestUnixTreeHelper$")
	child.Env = append(os.Environ(), "XJP_SECURITY_TREE_ROLE=grandchild")
	if err := child.Start(); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(os.Getenv("XJP_SECURITY_TREE_PID"), []byte(strconv.Itoa(child.Process.Pid)), 0o600); err != nil {
		t.Fatal(err)
	}
	if role == "parent-exit" {
		os.Exit(0)
	}
	time.Sleep(helperLifetime)
}

// TestUnixTreeCleanup 将僵尸进程视为停止，因为执行已结束，
// 最终回收由宿主 init 决定，不属于监督器仍在运行的子进程。
func TestUnixTreeCleanup(t *testing.T) {
	for _, role := range []string{"parent-stop", "parent-exit"} {
		t.Run(role, func(t *testing.T) {
			pidFile := filepath.Join(t.TempDir(), "grandchild.pid")
			command := exec.Command(os.Args[0], "-test.run=^TestUnixTreeHelper$")
			command.Env = append(os.Environ(), "XJP_SECURITY_TREE_ROLE="+role, "XJP_SECURITY_TREE_PID="+pidFile)
			child, err := process.Start(command)
			if err != nil {
				t.Fatal(err)
			}
			defer child.Stop(50 * time.Millisecond)
			grandchild := readFixturePID(t, pidFile)
			if role == "parent-stop" {
				if err := child.Stop(50 * time.Millisecond); err != nil {
					t.Fatal(err)
				}
			} else {
				select {
				case <-child.Done():
				case <-time.After(7 * time.Second):
					t.Fatal("normal parent exit did not finish descendant cleanup")
				}
			}
			assertStopped(t, command.Process.Pid)
			assertStopped(t, grandchild)
		})
	}
}

// readFixturePID 等待子进程明确回报启动，
// 不用固定休眠猜测孙进程已经存在。
func readFixturePID(t *testing.T, path string) int {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if contents, err := os.ReadFile(path); err == nil {
			pid, err := strconv.Atoi(string(contents))
			if err == nil {
				return pid
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatal("grandchild did not acknowledge startup")
	return 0
}

// assertStopped 检查真实 Linux 进程状态而非仅查看完成通道，
// 避免仍可运行的孤儿进程被误判为清理成功。
func assertStopped(t *testing.T, pid int) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		stat, err := os.ReadFile("/proc/" + strconv.Itoa(pid) + "/stat")
		if os.IsNotExist(err) {
			return
		}
		if err == nil {
			end := strings.LastIndex(string(stat), ")")
			if end >= 0 && len(stat) > end+2 && stat[end+2] == 'Z' {
				return
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("process %d still runnable after tree cleanup", pid)
}
