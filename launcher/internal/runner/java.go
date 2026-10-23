// @author kongweiguang
package runner

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"time"
)

var javaFeaturePattern = regexp.MustCompile(`^(?:1\.)?([0-9]+)(?:[._+\-].*)?$`)

// findExternalJava 优先使用显式 JAVA_HOME，其次查找 PATH，使用户无需增加启动器专属配置字段即可选择运行时。
func findExternalJava(minimumJava int) (string, error) {
	if javaHome := strings.TrimSpace(os.Getenv("JAVA_HOME")); javaHome != "" {
		candidate := filepath.Join(javaHome, "bin", javaExecutable())
		if isRegularFile(candidate) {
			return verifyJava(candidate, minimumJava)
		}
		return "", errors.New("JAVA_HOME does not contain bin/java")
	}
	candidate, err := exec.LookPath(javaExecutable())
	if err != nil {
		return "", errors.New("Java was not found; install a compatible JDK or package jdk.zip")
	}
	return verifyJava(candidate, minimumJava)
}

// verifyJava 探测 Java 可执行文件是否可用及其版本，因为文件存在并不代表可运行或满足受保护归档的最低要求。
func verifyJava(path string, minimumJava int) (string, error) {
	if !isRegularFile(path) {
		return "", errors.New("Java executable is not a regular file")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, path, "-version")
	output, err := command.CombinedOutput()
	if err != nil {
		if ctx.Err() != nil {
			return "", errors.New("Java version probe timed out")
		}
		return "", fmt.Errorf("Java executable failed its version probe: %w", err)
	}
	feature, err := parseJavaFeatureVersion(output)
	if err != nil {
		return "", err
	}
	if feature < minimumJava {
		return "", fmt.Errorf("Java feature version %d is below archive requirement %d", feature, minimumJava)
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return "", fmt.Errorf("resolve Java executable: %w", err)
	}
	return absolute, nil
}

// parseJavaFeatureVersion 同时解析旧式 1.x 和现代特性版本输出，避免选择的 Java 低于应用内 class 文件要求。
func parseJavaFeatureVersion(output []byte) (int, error) {
	for _, line := range strings.Split(string(output), "\n") {
		fields := strings.Fields(strings.TrimSpace(line))
		if len(fields) < 2 {
			continue
		}
		vendor := strings.ToLower(strings.Trim(fields[0], `"'`))
		if vendor != "java" && vendor != "openjdk" {
			continue
		}
		versionIndex := 1
		if len(fields) > 2 && strings.EqualFold(strings.Trim(fields[1], `"'`), "version") {
			versionIndex = 2
		}
		if versionIndex >= len(fields) {
			continue
		}
		version := strings.Trim(fields[versionIndex], `"'`)
		match := javaFeaturePattern.FindStringSubmatch(version)
		if len(match) != 2 {
			continue
		}
		feature, err := strconv.Atoi(match[1])
		if err != nil || feature < 1 || feature > 100 {
			return 0, errors.New("Java version probe returned an invalid feature version")
		}
		return feature, nil
	}
	return 0, errors.New("Java version probe did not report a supported feature version")
}

// javaExecutable 显式选择平台对应的二进制文件，因为 JAVA_HOME 路径不会经过 exec.LookPath 的 Windows PATHEXT 解析。
func javaExecutable() string {
	if runtime.GOOS == "windows" {
		return "java.exe"
	}
	return "java"
}

// isRegularFile 在执行 Java 前拒绝目录和设备路径。
func isRegularFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}
