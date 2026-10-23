// @author kongweiguang
package runner

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/kongweiguang/xjar-plus/launcher/internal/license"
	"github.com/kongweiguang/xjar-plus/launcher/internal/status"
)

type observedStopper struct {
	err     error
	observe func(time.Time)
}

// TestCleanupPrivateRuntimeRemovesAndAcceptsMissingDirectories 固定清理的幂等约定，覆盖到期和启动器关闭两条路径。
func TestCleanupPrivateRuntimeRemovesAndAcceptsMissingDirectories(t *testing.T) {
	root := filepath.Join(t.TempDir(), "private-runtime")
	if err := os.Mkdir(root, 0o700); err != nil {
		t.Fatalf("create private runtime directory: %v", err)
	}
	if err := os.WriteFile(filepath.Join(root, "app.jar"), []byte("fixture"), 0o600); err != nil {
		t.Fatalf("create private fixture: %v", err)
	}
	if err := cleanupPrivateRuntime(root); err != nil {
		t.Fatalf("cleanupPrivateRuntime() error = %v", err)
	}
	if _, err := os.Stat(root); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("private runtime still exists after cleanup: %v", err)
	}
	if err := cleanupPrivateRuntime(root); err != nil {
		t.Fatalf("cleanupPrivateRuntime() should accept a missing path: %v", err)
	}
}

// TestCleanupPrivateRuntimeRetriesTransientFailures 固定有界重试行为，避免杀毒软件或索引器的短暂延迟造成普通关闭泄漏。
func TestCleanupPrivateRuntimeRetriesTransientFailures(t *testing.T) {
	attempts := 0
	pauses := 0
	err := cleanupPrivateRuntimeWith("private-runtime", func(string) error {
		attempts++
		if attempts < 3 {
			return errors.New("temporary sharing violation")
		}
		return nil
	}, func(time.Duration) { pauses++ })
	if err != nil {
		t.Fatalf("cleanupPrivateRuntimeWith() error = %v", err)
	}
	if attempts != 3 || pauses != 2 {
		t.Fatalf("attempts/pauses = %d/%d, want 3/2", attempts, pauses)
	}
}

// TestCleanupPrivateRuntimeReportsExhaustedRetries 确保清理重试耗尽后不会记录或伪报为私有文件已移除。
func TestCleanupPrivateRuntimeReportsExhaustedRetries(t *testing.T) {
	attempts := 0
	err := cleanupPrivateRuntimeWith("private-runtime", func(string) error {
		attempts++
		return errors.New("persistent filesystem error")
	}, func(time.Duration) {})
	if err == nil {
		t.Fatal("cleanupPrivateRuntimeWith() succeeded despite persistent errors")
	}
	if attempts != privateCleanupTries {
		t.Fatalf("attempts = %d, want %d", attempts, privateCleanupTries)
	}
}

// StopUntil 在返回前调用观测钩子，使测试能够检查停止过程中的状态。
func (child observedStopper) StopUntil(deadline time.Time) error {
	if child.observe != nil {
		child.observe(deadline)
	}
	return child.err
}

// TestStopAndMarkNotRunningWaitsForConfirmedExit 固定优雅停止尚未完成时对外可观察的状态契约。
func TestStopAndMarkNotRunningWaitsForConfirmedExit(t *testing.T) {
	state := &status.State{}
	state.SetAppRunning(true)
	deadline := time.Now().Add(5 * time.Second)
	called := false
	child := observedStopper{observe: func(got time.Time) {
		called = true
		if !got.Equal(deadline) {
			t.Fatalf("stop deadline = %s, want %s", got, deadline)
		}
		if !state.Snapshot(time.Now()).AppRunning {
			t.Fatal("status reported the business process stopped before Stop completed")
		}
	}}

	if err := stopAndMarkNotRunning(state, child, deadline); err != nil {
		t.Fatalf("stopAndMarkNotRunning() error = %v", err)
	}
	if !called {
		t.Fatal("Stop was not called")
	}
	if state.Snapshot(time.Now()).AppRunning {
		t.Fatal("status still reports the business process running after confirmed stop")
	}
}

// TestStopAndMarkNotRunningPreservesStateOnFailure 确保进程树停止失败时不会被报告为已完成。
func TestStopAndMarkNotRunningPreservesStateOnFailure(t *testing.T) {
	state := &status.State{}
	state.SetAppRunning(true)
	stopErr := errors.New("process tree did not exit")
	called := false
	deadline := time.Now().Add(time.Second)
	child := observedStopper{err: stopErr, observe: func(time.Time) {
		called = true
		if !state.Snapshot(time.Now()).AppRunning {
			t.Fatal("status reported the process stopped while Stop was still running")
		}
	}}

	if err := stopAndMarkNotRunning(state, child, deadline); !errors.Is(err, stopErr) {
		t.Fatalf("stopAndMarkNotRunning() error = %v, want %v", err, stopErr)
	}
	if !called {
		t.Fatal("Stop was not called")
	}
	if !state.Snapshot(time.Now()).AppRunning {
		t.Fatal("status reported the business process stopped after Stop failed")
	}
}

// TestExpiryStopDeadlinePreservesTheBoundaryBudget 防止定时器延迟导致到期停机重新获得一段完整宽限期。
func TestExpiryStopDeadlinePreservesTheBoundaryBudget(t *testing.T) {
	const expiryUnix = int64(1_900_000_000)
	grant := &license.Grant{NotAfterUnix: expiryUnix}
	expiry := time.Unix(expiryUnix, 0)
	detected := expiry.Add(1500 * time.Millisecond)
	deadline := stopDeadline(grant, detected)
	want := expiry.Add(gracefulStopLimit)
	if !deadline.Equal(want) {
		t.Fatalf("expiry stop deadline = %s, want %s", deadline, want)
	}
	if remaining := deadline.Sub(detected); remaining != 3500*time.Millisecond {
		t.Fatalf("remaining grace after delayed detection = %s, want 3.5s", remaining)
	}
}

// TestOtherInvalidityUsesDetectionBudget 确保其他无效状态使用发现时刻计算预算，不套用无关的许可证到期时间。
func TestOtherInvalidityUsesDetectionBudget(t *testing.T) {
	detected := time.Unix(1_900_000_000, 0)
	grant := &license.Grant{NotAfterUnix: detected.Add(time.Hour).Unix()}
	deadline := stopDeadline(grant, detected)
	if want := detected.Add(gracefulStopLimit); !deadline.Equal(want) {
		t.Fatalf("invalid-at-start deadline = %s, want %s", deadline, want)
	}
}
