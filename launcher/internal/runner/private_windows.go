//go:build windows

// @author kongweiguang
package runner

import (
	"fmt"
	"os"

	"golang.org/x/sys/windows"
)

// secureTemp 将临时目录继承权限替换为仅所有者可访问且可由子进程继承的 DACL，防止其他本机账户读取解压文件。
func secureTemp(path string) error {
	if err := os.Chmod(path, 0o700); err != nil {
		return err
	}
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return fmt.Errorf("read current user SID: %w", err)
	}
	descriptor, err := windows.SecurityDescriptorFromString("D:P(A;OICI;FA;;;" + user.User.Sid.String() + ")")
	if err != nil {
		return fmt.Errorf("create private directory ACL: %w", err)
	}
	dacl, _, err := descriptor.DACL()
	if err != nil {
		return fmt.Errorf("read private directory ACL: %w", err)
	}
	if err := windows.SetNamedSecurityInfo(
		path,
		windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		nil,
		nil,
		dacl,
		nil,
	); err != nil {
		return fmt.Errorf("apply private directory ACL: %w", err)
	}
	return nil
}
