//go:build windows

// @author kongweiguang
package process

import "os/exec"

// childExitCode 保留 Windows 应用报告的退出状态。
func childExitCode(exitError *exec.ExitError) int {
	return exitError.ExitCode()
}
