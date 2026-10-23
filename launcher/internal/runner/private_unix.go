//go:build !windows

// @author kongweiguang
package runner

import "os"

// secureTemp 移除继承来的目录权限，因为解压出的应用和 JDK 可能包含客户代码，即使数据密钥仅保留在内存中。
func secureTemp(path string) error {
	return os.Chmod(path, 0o700)
}
