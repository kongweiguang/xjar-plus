//go:build windows

// @author kongweiguang
package process

import (
	"errors"
	"fmt"
	"os/exec"
	"sync/atomic"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// configure 创建关闭即终止的 Job 并挂起首个线程，确保 Job 先于后代进程建立归属；新控制台组用于隔离优雅 Ctrl-Break。
func configure(command *exec.Cmd) (uintptr, error) {
	job, err := windows.CreateJobObject(nil, nil)
	if err != nil {
		return 0, fmt.Errorf("create Windows process job: %w", err)
	}
	var limits windows.JOBOBJECT_EXTENDED_LIMIT_INFORMATION
	limits.BasicLimitInformation.LimitFlags = windows.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
	_, err = windows.SetInformationJobObject(
		job,
		windows.JobObjectExtendedLimitInformation,
		uintptr(unsafe.Pointer(&limits)),
		uint32(unsafe.Sizeof(limits)),
	)
	if err != nil {
		_ = windows.CloseHandle(job)
		return 0, fmt.Errorf("configure Windows process job: %w", err)
	}
	if command.SysProcAttr == nil {
		command.SysProcAttr = &syscall.SysProcAttr{}
	}
	command.SysProcAttr.CreationFlags |= windows.CREATE_NEW_PROCESS_GROUP | windows.CREATE_SUSPENDED
	return uintptr(job), nil
}

// attach 在恢复首个线程前将挂起的子进程分配到 Job，确保所有后代都受该 Job 管理并随应用一同停止。
func attach(command *exec.Cmd, handle uintptr) error {
	processHandle, err := windows.OpenProcess(windows.PROCESS_SET_QUOTA|windows.PROCESS_TERMINATE, false, uint32(command.Process.Pid))
	if err != nil {
		return fmt.Errorf("open application for Windows process job: %w", err)
	}
	defer windows.CloseHandle(processHandle)
	if err := windows.AssignProcessToJobObject(windows.Handle(handle), processHandle); err != nil {
		return fmt.Errorf("assign application to Windows process job: %w", err)
	}
	return resumeInitialThread(uint32(command.Process.Pid))
}

// resumeInitialThread 在子进程创建后代前定位其唯一挂起线程并恢复执行，封闭先启动后分配 Job 的逃逸窗口。
func resumeInitialThread(pid uint32) error {
	snapshot, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPTHREAD, 0)
	if err != nil {
		return fmt.Errorf("snapshot child threads: %w", err)
	}
	defer windows.CloseHandle(snapshot)
	entry := windows.ThreadEntry32{Size: uint32(unsafe.Sizeof(windows.ThreadEntry32{}))}
	if err := windows.Thread32First(snapshot, &entry); err != nil {
		return fmt.Errorf("find child thread: %w", err)
	}
	for {
		if entry.OwnerProcessID == pid {
			thread, err := windows.OpenThread(windows.THREAD_SUSPEND_RESUME, false, entry.ThreadID)
			if err != nil {
				return fmt.Errorf("open suspended child thread: %w", err)
			}
			previous, resumeErr := windows.ResumeThread(thread)
			_ = windows.CloseHandle(thread)
			if resumeErr != nil || previous != 1 {
				if resumeErr == nil {
					resumeErr = errors.New("child thread was not suspended")
				}
				return fmt.Errorf("resume child thread: %w", resumeErr)
			}
			return nil
		}
		if err := windows.Thread32Next(snapshot, &entry); err != nil {
			return fmt.Errorf("locate initial child thread: %w", err)
		}
	}
}

// graceful 只向子进程的控制台组发出停止请求；没有关联控制台时允许失败，因为 Stop 仍会执行有界强停。
func graceful(pid int, _ uintptr) error {
	return windows.GenerateConsoleCtrlEvent(windows.CTRL_BREAK_EVENT, uint32(pid))
}

// force 终止整个 Windows Job 而非仅终止 Java 直接子进程，以清理应用启动的孙进程。
func force(pid int, handle uintptr) error {
	if handle != 0 {
		return windows.TerminateJobObject(windows.Handle(handle), 1)
	}
	process, err := windows.OpenProcess(windows.PROCESS_TERMINATE, false, uint32(pid))
	if err != nil {
		return err
	}
	defer windows.CloseHandle(process)
	return windows.TerminateProcess(process, 1)
}

// cleanup 在 Wait 回收直接子进程后关闭 kill-on-close Job，确保正常退出的应用不会遗留仍在运行的后代。
func cleanup(handle uintptr, _ int, _ *atomic.Bool) {
	if handle != 0 {
		_ = windows.CloseHandle(windows.Handle(handle))
	}
}
