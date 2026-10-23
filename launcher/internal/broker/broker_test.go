// @author kongweiguang
package broker

import (
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"testing"
	"time"
)

// TestBrokerDeliversExactlyOneDataKey 验证固定回环请求格式、有界响应、EOF 行为和一次性交付约定。
func TestBrokerDeliversExactlyOneDataKey(t *testing.T) {
	dataKey, _ := hex.DecodeString("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	server, err := Start("artifact-id", dataKey)
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	env := server.Environment()
	portText := strings.TrimPrefix(env[0], "XJAR_PLUS_BROKER_PORT=")
	port, err := strconv.Atoi(portText)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = fmt.Fprintf(conn, "%s artifact-id\n", strings.TrimPrefix(env[1], "XJAR_PLUS_BROKER_TOKEN="))
	_ = conn.(*net.TCPConn).CloseWrite()
	_ = conn.SetReadDeadline(time.Now().Add(time.Second))
	response, err := io.ReadAll(conn)
	_ = conn.Close()
	if err != nil {
		t.Fatal(err)
	}
	if string(response) != string(dataKey) {
		t.Fatalf("broker response = %x, want %x", response, dataKey)
	}
	select {
	case <-server.Done():
		if err := server.Err(); err != nil {
			t.Fatalf("broker result = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("broker did not close after one successful delivery")
	}
}

// TestBrokerClosesAfterInvalidAuthentication 验证无效认证后关闭连接，避免一次性数据密钥套接字被反复尝试。
func TestBrokerClosesAfterInvalidAuthentication(t *testing.T) {
	server, err := Start("artifact", make([]byte, 32))
	if err != nil {
		t.Fatal(err)
	}
	defer server.Close()
	port, _ := strconv.Atoi(strings.TrimPrefix(server.Environment()[0], "XJAR_PLUS_BROKER_PORT="))
	conn, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), time.Second)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = fmt.Fprint(conn, "wrong artifact\n")
	_ = conn.(*net.TCPConn).CloseWrite()
	select {
	case <-server.Done():
		if server.Err() == nil {
			t.Fatal("invalid broker request did not record failure")
		}
	case <-time.After(time.Second):
		t.Fatal("broker stayed open after invalid authentication")
	}
	_ = conn.Close()
}
