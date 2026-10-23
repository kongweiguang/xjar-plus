//go:build !windows

// @author kongweiguang
package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"
)

// signalContext 捕获终止信号，使启动器先停止子进程树，再返回 shell 约定的退出状态。
func signalContext() (context.Context, <-chan os.Signal, func()) {
	ctx, cancel := context.WithCancel(context.Background())
	notifySignals := make(chan os.Signal, 1)
	received := make(chan os.Signal, 1)
	signal.Notify(notifySignals, os.Interrupt, syscall.SIGTERM)
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

// signalExitCode 保留 SIGTERM 的 shell 约定状态，并将 Ctrl-C 规范为 130。
func signalExitCode(sig os.Signal) int {
	if value, ok := sig.(syscall.Signal); ok {
		return 128 + int(value)
	}
	return 130
}
