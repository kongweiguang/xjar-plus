//go:build !windows

// @author kongweiguang
package process

import (
	"errors"
	"os/exec"
	"sync/atomic"
	"syscall"
	"time"
)

// configure 创建独立进程组，因为只向 Java PID 发信号会让 shell 或辅助进程在许可证到期后继续运行。
func configure(command *exec.Cmd) (uintptr, error) {
	command.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	return 0, nil
}

// attach 在 Unix 上无需额外操作，因为 Setpgid 会在创建进程时原子地将其放入进程组。
func attach(*exec.Cmd, uintptr) error {
	return nil
}

// graceful 在强制终止计时开始前请求整个进程组退出。
func graceful(pid int, _ uintptr) error {
	return syscall.Kill(-pid, syscall.SIGTERM)
}

// force 终止受管进程组中仍然存活的所有进程。
func force(pid int, _ uintptr) error {
	return syscall.Kill(-pid, syscall.SIGKILL)
}

// cleanup 在直接子进程退出后停止进程组残余成员，因为 Java 应用可能在主进程退出后留下后台辅助进程。
func cleanup(_ uintptr, pid int, forced *atomic.Bool) {
	if pid <= 0 {
		return
	}
	if forced != nil && forced.Load() {
		_ = syscall.Kill(-pid, syscall.SIGKILL)
		return
	}
	_ = syscall.Kill(-pid, syscall.SIGTERM)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if forced != nil && forced.Load() {
			_ = syscall.Kill(-pid, syscall.SIGKILL)
			return
		}
		if errors.Is(syscall.Kill(-pid, 0), syscall.ESRCH) {
			return
		}
		time.Sleep(25 * time.Millisecond)
	}
	_ = syscall.Kill(-pid, syscall.SIGKILL)
}
