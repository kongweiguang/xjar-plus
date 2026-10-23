//go:build windows

// @author kongweiguang
package main

import (
	"context"
	"os"
	"os/signal"
)

// signalContext 将控制台中断转换为取消信号，避免 Windows 构建依赖 Unix 专属信号。
func signalContext() (context.Context, <-chan os.Signal, func()) {
	ctx, cancel := context.WithCancel(context.Background())
	notifySignals := make(chan os.Signal, 1)
	received := make(chan os.Signal, 1)
	signal.Notify(notifySignals, os.Interrupt)
	go func() {
		select {
		case sig := <-notifySignals:
			received <- sig
			cancel()
		case <-ctx.Done():
		}
	}()
	return ctx, received, func() {
		signal.Stop(notifySignals)
		cancel()
	}
}

// signalExitCode 统一 Windows Ctrl-C 与 Unix 命令行调用方的退出码语义；Windows 此处仅提供 os.Interrupt。
func signalExitCode(os.Signal) int {
	return 130
}
