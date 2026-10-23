// @author kongweiguang
package process

import (
	"errors"
	"os/exec"
	"sync"
	"sync/atomic"
	"time"
)

// Child 管理业务进程组，使授权到期或启动器收到信号时能统一停止后代进程，避免遗留 Java 辅助进程。
type Child struct {
	command *exec.Cmd
	handle  uintptr
	done    chan struct{}
	mu      sync.RWMutex
	err     error
	forced  atomic.Bool
}

// Command 集中封装进程创建，使平台实现可设置进程树控制而不改变调用方的参数和环境语义。
func Command(name string, args ...string) *exec.Cmd {
	return exec.Command(name, args...)
}

// Start 在创建前配置平台进程树控制；Windows 会先挂起子进程，完成 Job 分配后再恢复，防止产生不受管理的后代。
func Start(command *exec.Cmd) (*Child, error) {
	handle, err := configure(command)
	if err != nil {
		return nil, err
	}
	if err := command.Start(); err != nil {
		cleanup(handle, 0, nil)
		return nil, err
	}
	if err := attach(command, handle); err != nil {
		_ = command.Process.Kill()
		_ = command.Wait()
		cleanup(handle, command.Process.Pid, nil)
		return nil, err
	}
	child := &Child{command: command, handle: handle, done: make(chan struct{})}
	go func() {
		err := command.Wait()
		cleanup(handle, command.Process.Pid, &child.forced)
		child.mu.Lock()
		child.err = err
		child.mu.Unlock()
		close(child.done)
	}()
	return child, nil
}

// Done 支持监督器的 select 循环而不消耗唯一的 Wait 结果，使到期、取消和自然退出可以安全竞争。
func (child *Child) Done() <-chan struct{} {
	return child.done
}

// Err 等待进程终止后再返回稳定的退出结果。
func (child *Child) Err() error {
	<-child.done
	child.mu.RLock()
	defer child.mu.RUnlock()
	return child.err
}

// Stop 将相对宽限时长转换为绝对截止时间，使调用方在停机前已耗时的情况下仍共用同一预算。
func (child *Child) Stop(grace time.Duration) error {
	return child.StopUntil(time.Now().Add(grace))
}

// StopUntil 只将截止时间前剩余时长交给进程树，使到期调用方能从许可证边界开始计算宽限预算。
func (child *Child) StopUntil(deadline time.Time) error {
	select {
	case <-child.done:
		return nil
	default:
	}
	_ = graceful(child.command.Process.Pid, child.handle)
	if remaining := time.Until(deadline); remaining > 0 {
		timer := time.NewTimer(remaining)
		defer timer.Stop()
		select {
		case <-child.done:
			return nil
		case <-timer.C:
		}
	}
	child.forced.Store(true)
	if err := force(child.command.Process.Pid, child.handle); err != nil {
		select {
		case <-child.done:
			return nil
		default:
			return err
		}
	}
	select {
	case <-child.done:
		return nil
	case <-time.After(2 * time.Second):
		return errors.New("child process tree did not exit after force termination")
	}
}

// ExitCode 保留应用显式退出状态，并将启动器等待失败映射为稳定的非零值供脚本判断。
func ExitCode(err error) int {
	if err == nil {
		return 0
	}
	var exitError *exec.ExitError
	if errors.As(err, &exitError) {
		return childExitCode(exitError)
	}
	return 1
}
