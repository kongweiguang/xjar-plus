// @author kongweiguang
package broker

import (
	"bufio"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

const (
	maxRequestBytes = 256
	maxLifetime     = 30 * time.Second
	readTimeout     = 5 * time.Second
)

// Server 通过回环连接提供一次经过认证的数据密钥读取，避免密钥进入子进程命令行、秘密文件或继承环境变量。
type Server struct {
	listener  *net.TCPListener
	token     string
	artifact  string
	dataKey   [32]byte
	expiresAt time.Time
	mu        sync.Mutex
	active    net.Conn
	closeOnce sync.Once
	done      chan struct{}
	err       error
}

// Start 绑定临时回环套接字并创建不可预测令牌；令牌有效期受协议限制，不延续至应用的整个运行期。
func Start(artifactID string, dataKey []byte) (*Server, error) {
	if len(dataKey) != 32 || len(artifactID) == 0 || len(artifactID) > 180 || strings.ContainsAny(artifactID, " \r\n\t") {
		return nil, errors.New("invalid broker key or artifactId")
	}
	listener, err := net.ListenTCP("tcp", &net.TCPAddr{IP: net.ParseIP("127.0.0.1"), Port: 0})
	if err != nil {
		return nil, fmt.Errorf("bind key broker: %w", err)
	}
	var tokenBytes [32]byte
	if _, err := rand.Read(tokenBytes[:]); err != nil {
		_ = listener.Close()
		return nil, fmt.Errorf("create broker token: %w", err)
	}
	server := &Server{
		listener: listener, token: hex.EncodeToString(tokenBytes[:]),
		artifact: artifactID, expiresAt: time.Now().Add(maxLifetime), done: make(chan struct{}),
	}
	copy(server.dataKey[:], dataKey)
	if err := listener.SetDeadline(time.Now().Add(maxLifetime)); err != nil {
		_ = listener.Close()
		return nil, fmt.Errorf("set broker lifetime: %w", err)
	}
	go server.serve()
	return server, nil
}

// Environment 只返回 Java 子进程完成一次性会合所需的参数，数据密钥不会进入进程元数据或日志。
func (server *Server) Environment() []string {
	server.mu.Lock()
	defer server.mu.Unlock()
	return []string{
		fmt.Sprintf("XJAR_PLUS_BROKER_PORT=%d", server.listener.Addr().(*net.TCPAddr).Port),
		"XJAR_PLUS_BROKER_TOKEN=" + server.token,
		"XJAR_PLUS_ARTIFACT_ID=" + server.artifact,
	}
}

// Done 在成功、请求格式错误、超时或显式关闭后关闭，使监督器能区分 broker 错误与业务进程退出。
func (server *Server) Done() <-chan struct{} {
	return server.done
}

// Err 在 Done 关闭后返回 broker 的最终结果。
func (server *Server) Err() error {
	server.mu.Lock()
	defer server.mu.Unlock()
	return server.err
}

// Close 在子进程退出或许可证失效时立即撤销会合通道，包括已连接到套接字的请求。
func (server *Server) Close() {
	server.finish(nil)
}

// serve 只接受一次连接，避免重试扩大猜测或重放短时本机令牌的机会窗口。
func (server *Server) serve() {
	conn, err := server.listener.AcceptTCP()
	if err != nil {
		server.finish(err)
		return
	}
	server.mu.Lock()
	server.active = conn
	server.mu.Unlock()
	defer func() {
		_ = conn.Close()
		server.finish(nil)
	}()
	select {
	case <-server.done:
		return
	default:
	}
	deadline := time.Now().Add(readTimeout)
	if server.expiresAt.Before(deadline) {
		deadline = server.expiresAt
	}
	if !deadline.After(time.Now()) {
		server.fail(errors.New("broker request arrived after expiry"))
		return
	}
	if err := conn.SetDeadline(deadline); err != nil {
		server.fail(err)
		return
	}
	request, err := readRequest(conn)
	if err != nil {
		server.fail(err)
		return
	}
	parts := strings.Split(string(request), " ")
	server.mu.Lock()
	token, artifact := server.token, server.artifact
	server.mu.Unlock()
	if len(parts) != 2 || subtle.ConstantTimeCompare([]byte(parts[0]), []byte(token)) != 1 || parts[1] != artifact {
		server.fail(errors.New("broker request authentication failed"))
		return
	}
	select {
	case <-server.done:
		return
	default:
	}
	server.mu.Lock()
	dataKey := server.dataKey
	server.mu.Unlock()
	defer clear(dataKey[:])
	if _, err := conn.Write(dataKey[:]); err != nil {
		server.fail(fmt.Errorf("write broker response: %w", err))
		return
	}
}

// readRequest 限制分配大小并拒绝回车符或无效 UTF-8，使两种语言运行时对单行请求作出完全一致的解释。
func readRequest(conn net.Conn) ([]byte, error) {
	reader := bufio.NewReaderSize(conn, maxRequestBytes+1)
	line, err := reader.ReadSlice('\n')
	if err != nil {
		if errors.Is(err, bufio.ErrBufferFull) || errors.Is(err, io.EOF) {
			return nil, errors.New("broker request is missing its bounded newline")
		}
		return nil, fmt.Errorf("read broker request: %w", err)
	}
	if len(line) > maxRequestBytes || len(line) < 2 || line[len(line)-1] != '\n' || line[len(line)-2] == '\r' || !utf8.Valid(line[:len(line)-1]) {
		return nil, errors.New("broker request size or encoding is invalid")
	}
	if reader.Buffered() != 0 {
		return nil, errors.New("broker request contains trailing bytes")
	}
	return line[:len(line)-1], nil
}

// fail 只记录通用错误，因为请求内容可能含令牌，不得复制到诊断信息中。
func (server *Server) fail(err error) {
	server.mu.Lock()
	server.err = err
	server.mu.Unlock()
}

// finish 仅关闭一次套接字和完成信号，避免超时、成功交付与进程取消并发时重复关闭。
func (server *Server) finish(err error) {
	server.closeOnce.Do(func() {
		server.mu.Lock()
		if server.err == nil && err != nil {
			server.err = err
		}
		active := server.active
		server.mu.Unlock()
		_ = server.listener.Close()
		if active != nil {
			_ = active.Close()
		}
		server.mu.Lock()
		for i := range server.dataKey {
			server.dataKey[i] = 0
		}
		server.token = ""
		server.mu.Unlock()
		close(server.done)
	})
}
