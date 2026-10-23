// @author kongweiguang
package securitytests

import (
	"context"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/kongweiguang/xjar-plus/launcher/internal/broker"
)

// TestJavaBrokerPreservesApplicationStdin 验证真实 Go 到 Java 套接字交接，
// 并确认 Java 取钥后一次性通道关闭。
func TestJavaBrokerPreservesApplicationStdin(t *testing.T) {
	for name, java := range javaExecutables() {
		t.Run(name, func(t *testing.T) {
			server, err := broker.Start("00112233-4455-4677-8899-aabbccddeeff", make([]byte, 32))
			if err != nil {
				t.Fatal(err)
			}
			defer server.Close()
			environment := server.Environment()
			runJavaBrokerProbe(t, java, environment)
			select {
			case <-server.Done():
			case <-time.After(time.Second):
				t.Fatal("broker remained alive after key delivery")
			}
			if err := server.Err(); err != nil {
				t.Fatal(err)
			}
			for _, value := range environment {
				if port, found := strings.CutPrefix(value, "XJAR_PLUS_BROKER_PORT="); found {
					connection, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", port), time.Second)
					if err == nil {
						connection.Close()
						t.Fatal("broker accepted a second connection")
					}
				}
			}
		})
	}
}

// TestJavaBrokerDoesNotFallbackToPassword 确认不完整的供钥环境配置
// 不能通过已配置的密码文件绕过供钥失败。
func TestJavaBrokerDoesNotFallbackToPassword(t *testing.T) {
	for name, java := range javaExecutables() {
		t.Run(name, func(t *testing.T) {
			runJavaBrokerProbe(t, java, []string{"XJAR_PLUS_BROKER_TOKEN=" + strings.Repeat("0", 64)}, "incomplete")
		})
	}
}

// runJavaBrokerProbe 向业务输入写入可辨认的一行，
// 临时交接参数仅放环境变量，不进入命令行或日志。
func runJavaBrokerProbe(t *testing.T, java string, additions []string, mode ...string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	arguments := []string{"-Dxjar.plus.password-file=must-not-be-read", "-cp", filepath.Join("target", "classes"),
		"io.github.kongweiguang.xjar.plus.runtime.common.RuntimeBrokerProbe"}
	command := exec.CommandContext(ctx, java, append(arguments, mode...)...)
	command.Stdin = strings.NewReader("application-stdin\n")
	for _, value := range os.Environ() {
		key, _, _ := strings.Cut(value, "=")
		if !strings.HasPrefix(strings.ToUpper(key), "XJAR_PLUS_") {
			command.Env = append(command.Env, value)
		}
	}
	command.Env = append(command.Env, additions...)
	output, err := command.CombinedOutput()
	if err != nil {
		t.Fatalf("Java broker probe failed: %v; output=%s", err, output)
	}
	if strings.TrimSpace(string(output)) != "BROKER_STDIN_OK" {
		t.Fatalf("unexpected Java probe result: %s", output)
	}
}

// javaExecutables 始终运行 PATH 中的 Java，并可额外指定真实 Java 8；
// 仅编译兼容不能当作运行验证。
func javaExecutables() map[string]string {
	result := map[string]string{"path-java": "java"}
	if java8 := os.Getenv("XJP_SECURITY_JAVA8"); java8 != "" {
		result["java8"] = java8
	}
	return result
}
