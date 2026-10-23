//go:build !windows

// @author kongweiguang
package process

import (
	"os/exec"
	"syscall"
)

// childExitCode 按 shell 约定映射信号终止状态，同时保留应用的普通数字退出码。
func childExitCode(exitError *exec.ExitError) int {
	status, ok := exitError.Sys().(syscall.WaitStatus)
	if ok && status.Signaled() {
		return 128 + int(status.Signal())
	}
	return exitError.ExitCode()
}
