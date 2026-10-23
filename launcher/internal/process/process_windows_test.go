//go:build windows

// @author kongweiguang
package process

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

const processStillActive = 259

// TestWindowsProcessTreeHelper 作为隐藏子进程和孙进程测试夹具运行，以验证 Job Object 清理且不弹出交互窗口。
func TestWindowsProcessTreeHelper(t *testing.T) {
	switch os.Getenv("XJP_PROCESS_TREE_ROLE") {
	case "parent":
		path := os.Getenv("XJP_PROCESS_TREE_PID_FILE")
		command := exec.Command(os.Args[0], "-test.run=^TestWindowsProcessTreeHelper$")
		command.Env = append(os.Environ(), "XJP_PROCESS_TREE_ROLE=grandchild", "XJP_PROCESS_TREE_PID_FILE="+path)
		command.SysProcAttr = &syscall.SysProcAttr{CreationFlags: windows.CREATE_NO_WINDOW}
		if err := command.Start(); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(strconv.Itoa(command.Process.Pid)), 0o600); err != nil {
			t.Fatal(err)
		}
		for {
			time.Sleep(time.Second)
		}
	case "grandchild":
		for {
			time.Sleep(time.Second)
		}
	}
}

// TestWindowsJobObjectTerminatesGrandchildren 启动隐藏的两级进程树，并验证短宽限期后会回退为终止整个 Job。
func TestWindowsJobObjectTerminatesGrandchildren(t *testing.T) {
	pidFile := filepath.Join(t.TempDir(), "grandchild.pid")
	command := exec.Command(os.Args[0], "-test.run=^TestWindowsProcessTreeHelper$")
	command.Env = append(os.Environ(), "XJP_PROCESS_TREE_ROLE=parent", "XJP_PROCESS_TREE_PID_FILE="+pidFile)
	command.SysProcAttr = &syscall.SysProcAttr{CreationFlags: windows.CREATE_NO_WINDOW}
	child, err := Start(command)
	if err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if _, err := os.Stat(pidFile); err == nil {
			break
		}
		time.Sleep(25 * time.Millisecond)
	}
	grandchildBytes, err := os.ReadFile(pidFile)
	if err != nil {
		_ = child.Stop(time.Second)
		t.Fatalf("grandchild pid was not recorded: %v", err)
	}
	grandchildPID, err := strconv.Atoi(strings.TrimSpace(string(grandchildBytes)))
	if err != nil {
		t.Fatal(err)
	}
	parentPID := command.Process.Pid
	if err := child.Stop(50 * time.Millisecond); err != nil {
		t.Fatalf("Stop() error = %v", err)
	}
	for _, pid := range []int{parentPID, grandchildPID} {
		if err := waitProcessGone(uint32(pid), 3*time.Second); err != nil {
			t.Fatal(err)
		}
	}
}

// waitProcessGone 轮询进程是否仍可打开，因为 Windows 可能在后代已退出后短暂保留进程对象。
func waitProcessGone(pid uint32, timeout time.Duration) error {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		process, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
		if err != nil {
			return nil
		}
		var code uint32
		queryErr := windows.GetExitCodeProcess(process, &code)
		_ = windows.CloseHandle(process)
		if queryErr == nil && code != processStillActive {
			return nil
		}
		time.Sleep(25 * time.Millisecond)
	}
	return fmt.Errorf("process %d remained active after timeout", pid)
}
