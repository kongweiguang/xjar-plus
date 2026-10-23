// @author kongweiguang
package status

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"testing"
	"time"

	"github.com/kongweiguang/xjar-plus/launcher/internal/license"
)

// TestConfiguredAddressPreservesLoopbackAndDefaultPort 验证可选端口设置不能改变绑定接口或单实例默认端口。
func TestConfiguredAddressPreservesLoopbackAndDefaultPort(t *testing.T) {
	for input, want := range map[string]string{
		"":     "127.0.0.1:19527",
		"0":    "127.0.0.1:0",
		"8080": "127.0.0.1:8080",
	} {
		got, err := configuredAddress(input)
		if err != nil || got != want {
			t.Fatalf("configuredAddress(%q) = %q, %v; want %q", input, got, err, want)
		}
	}
	for _, input := range []string{"-1", "65536", "1.5", "localhost:80"} {
		if _, err := configuredAddress(input); err == nil {
			t.Fatalf("configuredAddress(%q) accepted an invalid port", input)
		}
	}
}

// TestStartSupportsMultipleLoopbackInstances 验证端口 0 可让多个启动器实例隔离运行，同时状态服务仍不监听外部网络。
func TestStartSupportsMultipleLoopbackInstances(t *testing.T) {
	t.Setenv("XJAR_PLUS_STATUS_PORT", "0")
	servers := make([]*Server, 0, 2)
	ports := make(map[string]struct{}, 2)
	t.Cleanup(func() {
		for _, server := range servers {
			ctx, cancel := context.WithTimeout(context.Background(), time.Second)
			_ = server.Close(ctx)
			cancel()
		}
	})
	for range 2 {
		server, err := Start(&State{})
		if err != nil {
			t.Fatalf("Start() error = %v", err)
		}
		servers = append(servers, server)
		host, port, err := net.SplitHostPort(server.Addr())
		if err != nil || host != "127.0.0.1" || port == "0" {
			t.Fatalf("status address = %q; host=%q port=%q err=%v", server.Addr(), host, port, err)
		}
		if _, duplicate := ports[port]; duplicate {
			t.Fatalf("two local instances unexpectedly share port %s", port)
		}
		ports[port] = struct{}{}
		response, err := http.Get("http://" + server.Addr() + healthPath)
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusOK {
			t.Fatalf("health status = %d", response.StatusCode)
		}
		_ = response.Body.Close()
	}
}

// TestStartRejectsInvalidConfiguredPort 验证格式错误的端口覆盖不会静默回退到调用方未探测的其他监听端口。
func TestStartRejectsInvalidConfiguredPort(t *testing.T) {
	t.Setenv("XJAR_PLUS_STATUS_PORT", "65536")
	if _, err := Start(&State{}); err == nil {
		t.Fatal("Start() accepted an out-of-range status port")
	}
}

// TestStatusSeparatesLicenseAndApplicationState 确保授权无效时仍可查询状态，且业务进程被准确报告为已停止。
func TestStatusSeparatesLicenseAndApplicationState(t *testing.T) {
	state := &State{}
	state.SetLicense(nil, context.DeadlineExceeded)
	state.SetAppRunning(false)
	snapshot := state.Snapshot(time.Now)
	if snapshot.Available || snapshot.Valid || snapshot.AppRunning || snapshot.Message == "" {
		t.Fatalf("invalid license snapshot = %+v", snapshot)
	}
	grant := license.Grant{AppID: "demo", NotBeforeUnix: time.Now().Add(-time.Minute).Unix(), Perpetual: true}
	state.SetLicense(&grant, nil)
	snapshot = state.Snapshot(time.Now)
	if !snapshot.Available || !snapshot.Valid || snapshot.AppRunning {
		t.Fatalf("valid-but-stopped snapshot = %+v", snapshot)
	}
}

// TestSnapshotSamplesTimeWhileStateIsLocked 不靠调度或睡眠证明采时持有状态锁，同时固定截止前、截止点和停止后的授权边界。
func TestSnapshotSamplesTimeWhileStateIsLocked(t *testing.T) {
	expiry := time.Unix(1_900_000_000, 0).UTC()
	for _, testCase := range []struct {
		name        string
		now         time.Time
		running     bool
		valid       bool
		remainingMs int64
	}{
		{name: "截止前", now: expiry.Add(-time.Millisecond), running: true, valid: true, remainingMs: 1},
		{name: "截止瞬间", now: expiry, running: true, valid: false},
		{name: "停止完成", now: expiry.Add(time.Second), running: false, valid: false},
	} {
		t.Run(testCase.name, func(t *testing.T) {
			state := &State{}
			grant := license.Grant{AppID: "demo", NotBeforeUnix: expiry.Add(-time.Minute).Unix(), NotAfterUnix: expiry.Unix()}
			state.SetLicense(&grant, nil)
			state.SetAppRunning(testCase.running)
			clockCalls := 0
			sampledWithoutLock := false
			snapshot := state.Snapshot(func() time.Time {
				clockCalls++
				if state.mu.TryLock() {
					sampledWithoutLock = true
					state.mu.Unlock()
				}
				return testCase.now
			})
			if clockCalls != 1 || sampledWithoutLock {
				t.Fatalf("clock calls=%d, sampled without state lock=%t", clockCalls, sampledWithoutLock)
			}
			if !snapshot.Available || snapshot.Valid != testCase.valid || snapshot.AppRunning != testCase.running ||
				snapshot.RemainingMs != testCase.remainingMs || snapshot.ServerTime != testCase.now.Format(time.RFC3339) ||
				snapshot.ValidEndDate != expiry.Format(time.RFC3339) {
				t.Fatalf("inconsistent expiry snapshot: %+v", snapshot)
			}
		})
	}
}

// TestStatusHTTPMethodsKeepsServiceReadOnly 验证 GET/HEAD 行为并阻止变更方法；即使监听仅限回环，也维持只读边界。
func TestStatusHTTPMethodsKeepsServiceReadOnly(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	server := startWithListener(&State{}, listener)
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		_ = server.Close(ctx)
	}()
	base := "http://" + server.Addr()
	for _, method := range []string{http.MethodGet, http.MethodHead} {
		response, err := http.DefaultClient.Do(mustRequest(t, method, base+statusPath))
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusOK {
			t.Fatalf("%s status = %d", method, response.StatusCode)
		}
		if method == http.MethodGet {
			var snapshot Snapshot
			if err := json.NewDecoder(response.Body).Decode(&snapshot); err != nil {
				t.Fatal(err)
			}
			if snapshot.AppRunning {
				t.Fatal("invalid status should report appRunning=false")
			}
		}
		_ = response.Body.Close()
	}
	response, err := http.Post(base+statusPath, "application/json", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusMethodNotAllowed {
		t.Fatalf("POST status = %d, want 405", response.StatusCode)
	}
}

// mustRequest 内联创建测试请求并检查错误，避免忽略错误后把 URL 格式问题隐藏在 HTTP 契约断言中。
func mustRequest(t *testing.T, method, url string) *http.Request {
	t.Helper()
	request, err := http.NewRequest(method, url, nil)
	if err != nil {
		t.Fatal(err)
	}
	return request
}
