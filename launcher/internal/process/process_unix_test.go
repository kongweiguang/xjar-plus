//go:build !windows

// @author kongweiguang
package process

import "testing"

// TestExitCodePreservesExitAndSignalStatus 验证子进程正常退出或被 SIGTERM 终止时，命令行状态仍可供脚本准确判断。
func TestExitCodePreservesExitAndSignalStatus(t *testing.T) {
	for _, test := range []struct {
		name string
		args []string
		want int
	}{
		{name: "exit", args: []string{"-c", "exit 37"}, want: 37},
		{name: "signal", args: []string{"-c", "kill -TERM $$"}, want: 128 + 15},
	} {
		t.Run(test.name, func(t *testing.T) {
			child, err := Start(Command("sh", test.args...))
			if err != nil {
				t.Fatal(err)
			}
			<-child.Done()
			if got := ExitCode(child.Err()); got != test.want {
				t.Fatalf("ExitCode() = %d, want %d", got, test.want)
			}
		})
	}
}
