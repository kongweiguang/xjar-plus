// @author kongweiguang
package status

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/kongweiguang/xjar-plus/launcher/internal/license"
)

const (
	defaultAddress = "127.0.0.1:19527"
	statusPath     = "/xjp/license/status"
	healthPath     = "/xjp/healthz"
)

// Snapshot 保留既有状态字段并增加 appRunning，使运维方能区分许可证有效与业务进程运行两种状态。
type Snapshot struct {
	Available      bool   `json:"available"`
	Valid          bool   `json:"valid"`
	ValidStartDate string `json:"validStartDate"`
	ValidEndDate   string `json:"validEndDate"`
	Perpetual      bool   `json:"perpetual"`
	RemainingMs    int64  `json:"remainingMs"`
	Remaining      string `json:"remaining,omitempty"`
	ServerTime     string `json:"serverTime"`
	AppRunning     bool   `json:"appRunning"`
	Message        string `json:"message,omitempty"`
}

// State 保护轻量状态快照，因为 HTTP 请求与进程监督器会并发更新；发生业务错误时状态服务仍需保持运行。
type State struct {
	mu         sync.RWMutex
	grant      *license.Grant
	available  bool
	licenseErr string
	appRunning bool
}

// SetLicense 只保存经过清理的失败原因；文件路径和密码解析细节不应暴露给状态接口调用方。
func (state *State) SetLicense(grant *license.Grant, err error) {
	state.mu.Lock()
	defer state.mu.Unlock()
	state.grant = nil
	state.available = err == nil && grant != nil
	state.licenseErr = ""
	if state.available {
		copy := *grant
		state.grant = &copy
	} else {
		state.licenseErr = "license file is unavailable"
	}
}

// SetAppRunning 独立更新业务进程状态而不改写许可证有效性，使业务停止后仍可查询授权状态。
func (state *State) SetAppRunning(running bool) {
	state.mu.Lock()
	state.appRunning = running
	state.mu.Unlock()
}

// Snapshot 在同一读锁内采时并复制状态，避免将到期前时间与停机后状态拼接；clock 只能快速取时，不能回调状态写入。
func (state *State) Snapshot(clock func() time.Time) Snapshot {
	state.mu.RLock()
	now := clock()
	grant, available, licenseErr, appRunning := state.grant, state.available, state.licenseErr, state.appRunning
	state.mu.RUnlock()
	snapshot := Snapshot{
		Available: available, AppRunning: appRunning,
		ServerTime: now.UTC().Format(time.RFC3339),
	}
	if !available || grant == nil {
		snapshot.Message = licenseErr
		return snapshot
	}
	snapshot.Perpetual = grant.Perpetual
	snapshot.ValidStartDate = time.Unix(grant.NotBeforeUnix, 0).UTC().Format(time.RFC3339)
	if grant.Perpetual {
		snapshot.ValidEndDate = ""
		snapshot.Valid = now.UTC().Unix() >= grant.NotBeforeUnix
		if !snapshot.Valid {
			snapshot.Message = "application is not valid yet"
		}
		return snapshot
	}
	snapshot.ValidEndDate = time.Unix(grant.NotAfterUnix, 0).UTC().Format(time.RFC3339)
	valid, message, remaining := grant.Evaluate(now)
	snapshot.Valid = valid
	snapshot.RemainingMs = remaining.Milliseconds()
	if valid {
		snapshot.Remaining = formatDuration(remaining)
	} else {
		snapshot.Message = message
	}
	return snapshot
}

// Server 管理固定回环监听器及其关闭流程，使无效许可证时仍可查询健康状态，同时不向远端开放接口。
type Server struct {
	listener net.Listener
	http     *http.Server
}

// Start 只绑定 IPv4 回环地址；显式端口覆盖便于隔离测试或多实例运行，同时保留默认端口规则。
func Start(state *State) (*Server, error) {
	address, err := configuredAddress(os.Getenv("XJAR_PLUS_STATUS_PORT"))
	if err != nil {
		return nil, err
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return nil, fmt.Errorf("listen on %s: %w", address, err)
	}
	return startWithListener(state, listener), nil
}

// configuredAddress 将端口覆盖限制为数字且始终绑定回环地址，避免并行实例的便利设置意外暴露远程状态接口。
func configuredAddress(portValue string) (string, error) {
	portValue = strings.TrimSpace(portValue)
	if portValue == "" {
		return defaultAddress, nil
	}
	port, err := strconv.ParseUint(portValue, 10, 16)
	if err != nil {
		return "", errors.New("XJAR_PLUS_STATUS_PORT must be 0 or a port from 1 to 65535")
	}
	return net.JoinHostPort("127.0.0.1", strconv.FormatUint(port, 10)), nil
}

// startWithListener 集中配置严格处理器和有界超时，使测试无需占用默认端口也能验证相同契约。
func startWithListener(state *State, listener net.Listener) *Server {
	mux := http.NewServeMux()
	mux.HandleFunc(statusPath, func(w http.ResponseWriter, r *http.Request) {
		if !methodAllowed(w, r) {
			return
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if err := json.NewEncoder(w).Encode(state.Snapshot(time.Now)); err != nil {
			log.Printf("xjar-plus: write status response: %v", err)
		}
	})
	mux.HandleFunc(healthPath, func(w http.ResponseWriter, r *http.Request) {
		if !methodAllowed(w, r) {
			return
		}
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusOK)
		if r.Method != http.MethodHead {
			_, _ = w.Write([]byte("ok\n"))
		}
	})
	server := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    1 << 20,
	}
	result := &Server{listener: listener, http: server}
	go func() {
		if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("xjar-plus: status server stopped unexpectedly: %v", err)
		}
	}()
	return result
}

// Addr 返回实际本机端点供诊断使用，但不允许将监听地址改为回环之外的网络接口。
func (server *Server) Addr() string {
	return server.listener.Addr().String()
}

// Close 等待正在处理的本机请求结束，避免启动器清理子进程树时遗留半开的状态套接字。
func (server *Server) Close(ctx context.Context) error {
	err := server.http.Shutdown(ctx)
	if err != nil {
		_ = server.listener.Close()
	}
	return err
}

// methodAllowed 保持状态接口只读，避免浏览器自动产生的请求方法带来意外状态变更。
func methodAllowed(w http.ResponseWriter, r *http.Request) bool {
	if r.Method == http.MethodGet || r.Method == http.MethodHead {
		return true
	}
	w.Header().Set("Allow", "GET, HEAD")
	http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	return false
}

// formatDuration 统一格式化剩余时长，并隐藏签名线格式未表示的亚秒精度。
func formatDuration(value time.Duration) string {
	seconds := int64(value / time.Second)
	days := seconds / 86400
	seconds %= 86400
	hours := seconds / 3600
	seconds %= 3600
	minutes := seconds / 60
	seconds %= 60
	if days > 0 {
		return fmt.Sprintf("%dd %02dh %02dm %02ds", days, hours, minutes, seconds)
	}
	return fmt.Sprintf("%02dh %02dm %02ds", hours, minutes, seconds)
}
