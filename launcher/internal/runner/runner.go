// @author kongweiguang
package runner

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/kongweiguang/xjar-plus/launcher/internal/broker"
	"github.com/kongweiguang/xjar-plus/launcher/internal/config"
	"github.com/kongweiguang/xjar-plus/launcher/internal/jdk"
	"github.com/kongweiguang/xjar-plus/launcher/internal/license"
	"github.com/kongweiguang/xjar-plus/launcher/internal/process"
	"github.com/kongweiguang/xjar-plus/launcher/internal/status"
)

const (
	licenseFile         = "key.x"
	expiryCheckPeriod   = 250 * time.Millisecond
	licenseFileLimit    = 4096
	appFileLimit        = 1 << 31
	gracefulStopLimit   = 5 * time.Second
	privateCleanupTries = 5
	privateCleanupPause = 100 * time.Millisecond
)

// stoppableChild 仅暴露进程生命周期操作；只有该操作确认完成后，状态才能如实报告业务进程已停止。
type stoppableChild interface {
	StopUntil(time.Time) error
}

// ValidatePayload 在启动器报告就绪前发现打包错误，同时将授权校验留给持续运行的状态循环，以便 fail-closed 时仍可诊断。
func ValidatePayload(appJar []byte, cfg config.Config, jdkZip []byte) error {
	if err := cfg.Validate(); err != nil {
		return err
	}
	if len(appJar) == 0 || int64(len(appJar)) > appFileLimit {
		return errors.New("app.jar is empty or exceeds the supported size")
	}
	archive, err := zip.NewReader(bytes.NewReader(appJar), int64(len(appJar)))
	if err != nil || len(archive.File) == 0 {
		return errors.New("app.jar is not a readable ZIP/JAR archive")
	}
	if cfg.EmbeddedJDK && len(jdkZip) == 0 {
		return errors.New("embeddedJdk is enabled but jdk.zip is empty")
	}
	if !cfg.EmbeddedJDK && len(jdkZip) != 0 {
		return errors.New("jdk.zip is present while embeddedJdk is disabled")
	}
	return nil
}

// Run 在许可证缺失、损坏、尚未生效或已过期时仍保持状态服务运行；只有取得有效授权后才启动业务子进程。
func Run(ctx context.Context, cfg config.Config, appJar, jdkZip []byte, executableDir string) int {
	state := &status.State{}
	grant, licenseErr := loadLicense(executableDir, cfg)
	clear(cfg.LicenseKey)
	state.SetLicense(grant, licenseErr)
	server, err := status.Start(state)
	if err != nil {
		clear(cfg.DataKey)
		log.Printf("xjar-plus: start local license status service: %v", err)
		return 1
	}
	defer func() {
		closeCtx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		if err := server.Close(closeCtx); err != nil {
			log.Printf("xjar-plus: stop license status service: %v", err)
		}
	}()
	log.Printf("xjar-plus: license status service listening at http://%s/xjp/license/status", server.Addr())
	return supervise(ctx, state, cfg, appJar, jdkZip, grant, licenseErr)
}

// supervise 仅使用启动时读取的授权，防止替换 key.x 静默重新授权；到期后在受管业务进程树停止时立即清理私有运行文件，状态服务继续运行。
func supervise(ctx context.Context, state *status.State, cfg config.Config, appJar, jdkZip []byte, grant *license.Grant, licenseErr error) int {
	ticker := time.NewTicker(expiryCheckPeriod)
	defer ticker.Stop()
	if licenseErr != nil || grant == nil {
		clear(cfg.DataKey)
		log.Print("xjar-plus: license is unavailable or invalid; business process remains stopped until restart")
		return waitForShutdown(ctx)
	}
	valid, reason, _ := grant.Evaluate(time.Now())
	if !valid {
		clear(cfg.DataKey)
		log.Printf("xjar-plus: license %s; business process remains stopped until restart", reason)
		return waitForShutdown(ctx)
	}
	privateRoot, appPath, javaPath, err := prepareRuntime(appJar, jdkZip, cfg.EmbeddedJDK, cfg.MinimumJava)
	if err != nil {
		clear(cfg.DataKey)
		log.Printf("xjar-plus: prepare runtime: %v", err)
		return 1
	}
	var child *process.Child
	defer func() {
		if child != nil {
			select {
			case <-child.Done():
			default:
				log.Printf("xjar-plus: retain private runtime directory while the business process tree is still running")
				return
			}
		}
		if err := cleanupPrivateRuntime(privateRoot); err != nil {
			log.Printf("xjar-plus: clean private runtime directory: %v", err)
		}
	}()
	if ctx.Err() != nil {
		clear(cfg.DataKey)
		return 0
	}
	valid, _, _ = grant.Evaluate(time.Now())
	if !valid {
		clear(cfg.DataKey)
		if err := cleanupPrivateRuntime(privateRoot); err != nil {
			log.Printf("xjar-plus: clean private runtime directory before waiting for restart: %v", err)
		}
		return waitForShutdown(ctx)
	}
	startedChild, keyBroker, err := startApplication(javaPath, appPath, cfg)
	if err != nil {
		log.Printf("xjar-plus: start application: %v", err)
		return 1
	}
	child = startedChild
	state.SetAppRunning(true)
	brokerDone := keyBroker.Done()
	for {
		select {
		case <-ctx.Done():
			keyBroker.Close()
			if err := stopAndMarkNotRunning(state, child, time.Now().Add(gracefulStopLimit)); err != nil {
				log.Printf("xjar-plus: stop child after signal: %v", err)
				return 1
			}
			return 0
		case <-child.Done():
			keyBroker.Close()
			state.SetAppRunning(false)
			return process.ExitCode(child.Err())
		case <-brokerDone:
			brokerErr := keyBroker.Err()
			brokerDone = nil
			if brokerErr != nil {
				if err := stopAndMarkNotRunning(state, child, time.Now().Add(gracefulStopLimit)); err != nil {
					log.Printf("xjar-plus: stop child after data-key broker failure: %v", err)
				}
				log.Printf("xjar-plus: data-key broker failed before delivery")
				return 1
			}
		case <-ticker.C:
			now := time.Now()
			stillValid, _, _ := grant.Evaluate(now)
			if !stillValid {
				keyBroker.Close()
				log.Printf("xjar-plus: license is no longer valid; stopping application")
				if err := stopAndMarkNotRunning(state, child, stopDeadline(grant, now)); err != nil {
					log.Printf("xjar-plus: stop expired application: %v", err)
					return waitForShutdown(ctx)
				}
				if err := cleanupPrivateRuntime(privateRoot); err != nil {
					log.Printf("xjar-plus: clean private runtime directory after expiry: %v", err)
				}
				return waitForShutdown(ctx)
			}
		}
	}
}

// cleanupPrivateRuntime 有界重试短暂的文件共享冲突，因为 Windows 杀毒软件或索引器可能短暂占用刚解压的 JDK 文件。
func cleanupPrivateRuntime(root string) error {
	return cleanupPrivateRuntimeWith(root, os.RemoveAll, time.Sleep)
}

// cleanupPrivateRuntimeWith 限定重试时长并接受文件系统适配器，使瞬时 Windows 式错误可被测试而不依赖时序竞争。
func cleanupPrivateRuntimeWith(root string, removeAll func(string) error, pause func(time.Duration)) error {
	var lastErr error
	for attempt := 0; attempt < privateCleanupTries; attempt++ {
		if err := removeAll(root); err == nil {
			return nil
		} else {
			lastErr = err
		}
		if attempt+1 < privateCleanupTries {
			pause(time.Duration(attempt+1) * privateCleanupPause)
		}
	}
	return fmt.Errorf("remove private runtime directory after %d attempts: %w", privateCleanupTries, lastErr)
}

// stopDeadline 将到期停机预算锚定到已认证的排他结束时刻；启动即无效或时钟回拨时仍从发现时刻给予有界停机窗口。
func stopDeadline(grant *license.Grant, detectedAt time.Time) time.Time {
	if grant != nil && !grant.Perpetual && detectedAt.UTC().Unix() >= grant.NotAfterUnix {
		return time.Unix(grant.NotAfterUnix, 0).Add(gracefulStopLimit)
	}
	return detectedAt.Add(gracefulStopLimit)
}

// stopAndMarkNotRunning 只有在进程层确认受管进程树已退出后才更新状态；停止失败时保留真实运行状态。
func stopAndMarkNotRunning(state *status.State, child stoppableChild, deadline time.Time) error {
	if err := child.StopUntil(deadline); err != nil {
		return err
	}
	state.SetAppRunning(false)
	return nil
}

// waitForShutdown 在许可证无效或尚未生效时阻止业务启动，同时保留状态服务供诊断，直至重启启动器。
func waitForShutdown(ctx context.Context) int {
	<-ctx.Done()
	return 0
}

// loadLicense 拒绝符号链接并有界读取同目录 key.x，因为许可证替换是由运维方控制的运行时输入。
func loadLicense(executableDir string, cfg config.Config) (*license.Grant, error) {
	path := filepath.Join(executableDir, licenseFile)
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Size() <= 0 || info.Size() > licenseFileLimit {
		return nil, errors.New("license file type or size is invalid")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	opened, err := file.Stat()
	if err != nil || !opened.Mode().IsRegular() || opened.Size() <= 0 || opened.Size() > licenseFileLimit || !os.SameFile(info, opened) {
		return nil, errors.New("license file changed while it was being opened")
	}
	data, err := io.ReadAll(io.LimitReader(file, licenseFileLimit+1))
	if err != nil {
		return nil, err
	}
	if len(data) > licenseFileLimit {
		return nil, errors.New("license file exceeds its size limit")
	}
	grant, err := license.Parse(data, cfg.LicenseKey, cfg.AppID)
	if err != nil {
		return nil, err
	}
	return &grant, nil
}

// prepareRuntime 在同一私有目录中准备应用和可选 JDK，并在启动前校验运行时是否满足归档 class 文件要求。
func prepareRuntime(appJar, jdkZip []byte, embeddedJDK bool, minimumJava int) (string, string, string, error) {
	root, err := os.MkdirTemp("", "xjar-plus-private-")
	if err != nil {
		return "", "", "", fmt.Errorf("create private runtime directory: %w", err)
	}
	if err := secureTemp(root); err != nil {
		_ = os.RemoveAll(root)
		return "", "", "", fmt.Errorf("protect private runtime directory: %w", err)
	}
	appPath := filepath.Join(root, "app.jar")
	if err := os.WriteFile(appPath, appJar, 0o600); err != nil {
		_ = os.RemoveAll(root)
		return "", "", "", fmt.Errorf("write private app.jar: %w", err)
	}
	if !embeddedJDK {
		javaPath, err := findExternalJava(minimumJava)
		if err != nil {
			_ = os.RemoveAll(root)
			return "", "", "", err
		}
		return root, appPath, javaPath, nil
	}
	javaPath, err := jdk.Extract(filepath.Join(root, "jdk"), jdkZip, minimumJava)
	if err != nil {
		_ = os.RemoveAll(root)
		return "", "", "", err
	}
	return root, appPath, javaPath, nil
}

// startApplication 保留 argv 和继承的 stdin；子进程取得 broker 环境参数后，父进程立即移除这些引用。
func startApplication(javaPath, appPath string, cfg config.Config) (*process.Child, *broker.Server, error) {
	defer clear(cfg.DataKey)
	keyBroker, err := broker.Start(cfg.ArtifactID, cfg.DataKey)
	if err != nil {
		return nil, nil, err
	}
	args := make([]string, 0, len(cfg.JVMArgs)+len(cfg.AppArgs)+2)
	args = append(args, cfg.JVMArgs...)
	args = append(args, "-jar", appPath)
	args = append(args, cfg.AppArgs...)
	command := process.Command(javaPath, args...)
	command.Stdin = os.Stdin
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	command.Env = childEnvironment(os.Environ(), keyBroker.Environment())
	child, err := process.Start(command)
	command.Env = nil
	if err != nil {
		keyBroker.Close()
		return nil, nil, err
	}
	return child, keyBroker, nil
}

// childEnvironment 替换继承的 broker 环境变量，防止无关父进程环境将运行时指向过期或被攻击者指定的套接字。
func childEnvironment(parent, additions []string) []string {
	filtered := make([]string, 0, len(parent)+len(additions))
	for _, item := range parent {
		key, _, _ := strings.Cut(item, "=")
		if strings.EqualFold(key, "XJAR_PLUS_BROKER_PORT") || strings.EqualFold(key, "XJAR_PLUS_BROKER_TOKEN") || strings.EqualFold(key, "XJAR_PLUS_ARTIFACT_ID") {
			continue
		}
		filtered = append(filtered, item)
	}
	return append(filtered, additions...)
}
