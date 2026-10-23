// @author kongweiguang
package main

import (
	"context"
	"embed"
	"fmt"
	"os"
	"path/filepath"

	"github.com/kongweiguang/xjar-plus/launcher/internal/config"
	"github.com/kongweiguang/xjar-plus/launcher/internal/runner"
)

//go:embed payload/*
var payload embed.FS

// main 明确限定嵌入载荷边界，使仓库源码在缺少真实载荷时仍可编译，但不会误生成可运行的空配置启动器。
func main() {
	ctx, signals, stopSignals := signalContext()
	defer stopSignals()
	code := run(ctx)
	if ctx.Err() != nil {
		select {
		case sig := <-signals:
			if sig != nil {
				code = signalExitCode(sig)
			}
		default:
			code = 130
		}
	}
	os.Exit(code)
}

// run 在启动服务前校验打包内容；许可证错误交由带状态服务的 runner 处理，便于运维诊断并在换证后重启。
func run(ctx context.Context) int {
	configBytes, err := payload.ReadFile("payload/config.json")
	if err != nil {
		fmt.Fprintln(os.Stderr, "xjar-plus launcher: no package configuration is embedded; build this launcher through the Java packager")
		return 2
	}
	cfg, err := config.Parse(configBytes)
	clear(configBytes)
	if err != nil {
		fmt.Fprintf(os.Stderr, "xjar-plus launcher: invalid embedded configuration: %v\n", err)
		return 2
	}
	appJar, err := payload.ReadFile("payload/app.jar")
	if err != nil {
		fmt.Fprintln(os.Stderr, "xjar-plus launcher: embedded app.jar is missing")
		return 2
	}
	var jdkZip []byte
	if cfg.EmbeddedJDK {
		jdkZip, err = payload.ReadFile("payload/jdk.zip")
		if err != nil {
			fmt.Fprintln(os.Stderr, "xjar-plus launcher: embeddedJdk is enabled but jdk.zip is missing")
			return 2
		}
	}
	exePath, err := os.Executable()
	if err != nil {
		fmt.Fprintf(os.Stderr, "xjar-plus launcher: locate executable: %v\n", err)
		return 2
	}
	if err := runner.ValidatePayload(appJar, cfg, jdkZip); err != nil {
		fmt.Fprintf(os.Stderr, "xjar-plus launcher: invalid package payload: %v\n", err)
		return 2
	}
	return runner.Run(ctx, cfg, appJar, jdkZip, filepath.Dir(exePath))
}
