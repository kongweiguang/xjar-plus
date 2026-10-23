// @author kongweiguang
package jdk

import (
	"archive/zip"
	"bytes"
	"debug/elf"
	"debug/macho"
	"debug/pe"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

const (
	maxFiles           = 100_000
	maxExpandedBytes   = uint64(8 << 30)
	maxEntryBytes      = uint64(2 << 30)
	maxReleaseFileSize = 64 * 1024
	maxLinkTargetBytes = 4096
	maxLinkDepth       = 40
)

var javaVersionPattern = regexp.MustCompile(`^(?:1\.)?([0-9]+)(?:[._+\-].*)?$`)

type archiveEntry struct {
	file       *zip.File
	relative   string
	directory  bool
	symlink    bool
	linkTarget string
}

// Extract 在向启动器返回可执行内容前校验归档路径、展开上限、目标平台元数据和最低 Java 特性版本。
func Extract(destination string, archive []byte, minimumJava int) (string, error) {
	if minimumJava < 8 || minimumJava > 100 {
		return "", errors.New("minimum Java feature version must be between 8 and 100")
	}
	reader, err := zip.NewReader(bytes.NewReader(archive), int64(len(archive)))
	if err != nil {
		return "", fmt.Errorf("open embedded JDK zip: %w", err)
	}
	if len(reader.File) == 0 || len(reader.File) > maxFiles {
		return "", errors.New("embedded JDK file count is invalid")
	}
	rootName, err := archiveRoot(reader.File)
	if err != nil {
		return "", err
	}
	entries, err := collectEntries(reader.File, rootName)
	if err != nil {
		return "", err
	}
	if err := validateEntryParents(entries); err != nil {
		return "", err
	}
	if err := validateSymlinks(entries); err != nil {
		return "", err
	}
	if err := validateArchiveRelease(entries, minimumJava); err != nil {
		return "", err
	}
	if err := os.Mkdir(destination, 0o700); err != nil {
		return "", fmt.Errorf("create private JDK extraction root: %w", err)
	}
	if err := extractSymlinks(destination, entries); err != nil {
		return "", err
	}
	for _, entry := range entries {
		if entry.directory {
			if err := os.MkdirAll(filepath.Join(destination, filepath.FromSlash(entry.relative)), 0o700); err != nil {
				return "", fmt.Errorf("create JDK directory: %w", err)
			}
			continue
		}
		if entry.symlink {
			continue
		}
		if err := extractFile(destination, entry.relative, entry.file); err != nil {
			return "", err
		}
	}
	return findJava(destination, minimumJava)
}

// validateArchiveRelease 在创建文件前检查所选 JDK 根目录，避免平台不符或版本过低时留下不完整运行时目录。
func validateArchiveRelease(entries []archiveEntry, minimumJava int) error {
	byPath := make(map[string]archiveEntry, len(entries))
	links := make(map[string]archiveEntry)
	for _, entry := range entries {
		byPath[entry.relative] = entry
		if entry.symlink {
			links[entry.relative] = entry
		}
	}
	var releaseEntry archiveEntry
	for _, candidate := range []string{"Contents/Home/release", "Home/release", "release"} {
		resolved, err := resolveArchivePath(candidate, byPath, links, make(map[string]bool))
		if err != nil {
			continue
		}
		entry, exists := byPath[resolved]
		if !exists {
			continue
		}
		if releaseEntry.file != nil {
			return errors.New("embedded JDK contains multiple ambiguous runtime roots")
		}
		releaseEntry = entry
	}
	if releaseEntry.file == nil || releaseEntry.directory || releaseEntry.symlink {
		return errors.New("embedded JDK release metadata is missing or not a regular file")
	}
	if releaseEntry.file.UncompressedSize64 > maxReleaseFileSize {
		return errors.New("embedded JDK release metadata exceeds its size limit")
	}
	input, err := releaseEntry.file.Open()
	if err != nil {
		return fmt.Errorf("open embedded JDK release metadata: %w", err)
	}
	data, readErr := io.ReadAll(io.LimitReader(input, maxReleaseFileSize+1))
	closeErr := input.Close()
	if readErr != nil {
		return fmt.Errorf("read embedded JDK release metadata: %w", readErr)
	}
	if closeErr != nil {
		return fmt.Errorf("close embedded JDK release metadata: %w", closeErr)
	}
	if len(data) > maxReleaseFileSize {
		return errors.New("embedded JDK release metadata exceeds its size limit")
	}
	attributes, err := parseRelease(data)
	if err != nil {
		return err
	}
	if err := validateTarget(attributes); err != nil {
		return err
	}
	return validateJavaFeature(attributes["JAVA_VERSION"], minimumJava)
}

// collectEntries 在写入前归一化所有 ZIP 节点，避免靠后的异常条目借已创建路径逃逸到目标根目录之外。
func collectEntries(files []*zip.File, rootName string) ([]archiveEntry, error) {
	entries := make([]archiveEntry, 0, len(files))
	seen := make(map[string]struct{}, len(files))
	var expanded uint64
	for _, file := range files {
		if file.Flags&(1|1<<6) != 0 {
			return nil, fmt.Errorf("embedded JDK contains an encrypted entry %q", file.Name)
		}
		if file.Method != zip.Store && file.Method != zip.Deflate {
			return nil, fmt.Errorf("embedded JDK contains an unsupported compression method at %q", file.Name)
		}
		relative, isDirectory, err := safeName(file.Name)
		if err != nil {
			return nil, err
		}
		if isDirectory && file.UncompressedSize64 != 0 {
			return nil, fmt.Errorf("embedded JDK directory entry is not empty at %q", relative)
		}
		if rootName != "" {
			if relative == rootName {
				continue
			}
			relative = strings.TrimPrefix(relative, rootName+"/")
		}
		if relative == "" {
			continue
		}
		key := strings.ToLower(filepath.ToSlash(relative))
		if _, duplicate := seen[key]; duplicate {
			return nil, fmt.Errorf("embedded JDK contains duplicate path %q", relative)
		}
		seen[key] = struct{}{}
		isSymlink := file.Mode()&os.ModeSymlink != 0
		if isSymlink && (runtime.GOOS == "windows" || isDirectory) {
			return nil, fmt.Errorf("embedded JDK symlink is unsupported on this target at %q", relative)
		}
		if file.Mode()&os.ModeType != 0 && !file.Mode().IsDir() && !isSymlink {
			return nil, fmt.Errorf("embedded JDK contains unsupported file type at %q", relative)
		}
		if file.UncompressedSize64 > maxEntryBytes || expanded > maxExpandedBytes-file.UncompressedSize64 {
			return nil, errors.New("embedded JDK exceeds extraction size limits")
		}
		expanded += file.UncompressedSize64
		entry := archiveEntry{file: file, relative: relative, directory: isDirectory, symlink: isSymlink}
		if isSymlink {
			entry.linkTarget, err = readLinkTarget(file)
			if err != nil {
				return nil, fmt.Errorf("read JDK symlink %q: %w", relative, err)
			}
		}
		entries = append(entries, entry)
	}
	return entries, nil
}

// readLinkTarget 有界读取并校验 ZIP 符号链接载荷，因为保留解压时这些字节会成为文件系统路径指令。
func readLinkTarget(file *zip.File) (string, error) {
	if file.UncompressedSize64 == 0 || file.UncompressedSize64 > maxLinkTargetBytes {
		return "", errors.New("symlink target size is invalid")
	}
	input, err := file.Open()
	if err != nil {
		return "", err
	}
	data, readErr := io.ReadAll(io.LimitReader(input, maxLinkTargetBytes+1))
	closeErr := input.Close()
	if readErr != nil {
		return "", readErr
	}
	if closeErr != nil {
		return "", closeErr
	}
	if len(data) == 0 || len(data) > maxLinkTargetBytes || uint64(len(data)) != file.UncompressedSize64 || !utf8.Valid(data) {
		return "", errors.New("symlink target is truncated or not bounded UTF-8")
	}
	target := string(data)
	if strings.ContainsAny(target, "\\:\x00") || path.IsAbs(target) {
		return "", errors.New("symlink target must be a safe relative path")
	}
	return target, nil
}

// validateEntryParents 在解压前拒绝文件与目录路径冲突，同时允许已验证的目录符号链接提供根内别名。
func validateEntryParents(entries []archiveEntry) error {
	byPath := make(map[string]archiveEntry, len(entries))
	for _, entry := range entries {
		byPath[entry.relative] = entry
	}
	for _, entry := range entries {
		for parent := path.Dir(entry.relative); parent != "."; parent = path.Dir(parent) {
			ancestor, exists := byPath[parent]
			if exists && !ancestor.directory && !ancestor.symlink {
				return fmt.Errorf("embedded JDK file %q is a parent of another entry", parent)
			}
		}
	}
	return nil
}

// validateSymlinks 在创建链接前沿归档节点图解析所有相对链接，提前拒绝越界、目标缺失和循环引用。
func validateSymlinks(entries []archiveEntry) error {
	byPath := make(map[string]archiveEntry, len(entries))
	links := make(map[string]archiveEntry)
	for _, entry := range entries {
		byPath[entry.relative] = entry
		if entry.symlink {
			links[entry.relative] = entry
		}
	}
	for _, entry := range entries {
		if !entry.symlink {
			continue
		}
		candidate := path.Clean(path.Join(path.Dir(entry.relative), entry.linkTarget))
		if path.IsAbs(candidate) || candidate == ".." || strings.HasPrefix(candidate, "../") {
			return fmt.Errorf("embedded JDK symlink %q escapes its root", entry.relative)
		}
		if _, err := resolveArchivePath(candidate, byPath, links, map[string]bool{entry.relative: true}); err != nil {
			return fmt.Errorf("embedded JDK symlink %q: %w", entry.relative, err)
		}
	}
	return nil
}

// resolveArchivePath 解析路径的每一级链接而非只解析末级，防止越界藏在表面相对的前缀路径后。
func resolveArchivePath(candidate string, entries, links map[string]archiveEntry, visiting map[string]bool) (string, error) {
	clean := path.Clean(candidate)
	if path.IsAbs(clean) || clean == ".." || strings.HasPrefix(clean, "../") {
		return "", errors.New("resolved symlink path escapes its root")
	}
	if clean == "." {
		return "", nil
	}
	parts := strings.Split(clean, "/")
	for index := range parts {
		prefix := strings.Join(parts[:index+1], "/")
		link, isLink := links[prefix]
		if !isLink {
			continue
		}
		if visiting[prefix] {
			return "", errors.New("symlink chain contains a cycle")
		}
		if len(visiting) >= maxLinkDepth {
			return "", errors.New("symlink chain exceeds its depth limit")
		}
		visiting[prefix] = true
		target := path.Clean(path.Join(path.Dir(prefix), link.linkTarget))
		if index+1 < len(parts) {
			target = path.Join(target, path.Join(parts[index+1:]...))
		}
		resolved, err := resolveArchivePath(target, entries, links, visiting)
		delete(visiting, prefix)
		return resolved, err
	}
	if !archivePathExists(clean, entries) {
		return "", fmt.Errorf("symlink target %q is missing", clean)
	}
	return clean, nil
}

// archivePathExists 接受显式节点和隐含父目录，但拒绝会让最终运行时留下悬空链接的缺失叶子文件。
func archivePathExists(candidate string, entries map[string]archiveEntry) bool {
	if candidate == "" || candidate == "." {
		return true
	}
	if _, exists := entries[candidate]; exists {
		return true
	}
	prefix := candidate + "/"
	for entryPath := range entries {
		if strings.HasPrefix(entryPath, prefix) {
			return true
		}
	}
	return false
}

// extractSymlinks 只保留已针对完整归档预先校验的链接；先创建链接再写文件，使合法别名在写入时即可解析。
func extractSymlinks(destination string, entries []archiveEntry) error {
	links := make([]archiveEntry, 0)
	for _, entry := range entries {
		if entry.symlink {
			links = append(links, entry)
		}
	}
	sort.Slice(links, func(left, right int) bool {
		return strings.Count(links[left].relative, "/") < strings.Count(links[right].relative, "/")
	})
	for _, entry := range links {
		linkPath := filepath.Join(destination, filepath.FromSlash(entry.relative))
		if !withinDestination(destination, linkPath) {
			return fmt.Errorf("embedded JDK symlink path escapes extraction root: %q", entry.relative)
		}
		if err := os.MkdirAll(filepath.Dir(linkPath), 0o700); err != nil {
			return fmt.Errorf("create JDK symlink parent directory: %w", err)
		}
		if err := os.Symlink(filepath.FromSlash(entry.linkTarget), linkPath); err != nil {
			return fmt.Errorf("create embedded JDK symlink: %w", err)
		}
	}
	return nil
}

// archiveRoot 仅在 ZIP 不含根文件且条目共享顶层目录时剥离这一层，使有无外包目录的厂商归档都可识别。
func archiveRoot(files []*zip.File) (string, error) {
	root := ""
	for _, file := range files {
		name, _, err := safeName(file.Name)
		if err != nil {
			return "", err
		}
		parts := strings.Split(name, "/")
		if len(parts) == 1 {
			if !file.FileInfo().IsDir() {
				return "", nil
			}
			continue
		}
		if root == "" {
			root = parts[0]
		} else if root != parts[0] {
			return "", nil
		}
	}
	return root, nil
}

// safeName 在与目标路径拼接前规范化归档路径；拒绝反斜杠和盘符语法以规避平台相关的路径穿越。
func safeName(name string) (string, bool, error) {
	if name == "" || len(name) > 4096 || !utf8.ValidString(name) || strings.ContainsAny(name, "\\:\x00") || strings.HasPrefix(name, "/") {
		return "", false, fmt.Errorf("embedded JDK contains unsafe path %q", name)
	}
	directory := strings.HasSuffix(name, "/")
	clean := strings.TrimSuffix(name, "/")
	for _, part := range strings.Split(clean, "/") {
		if part == "" || part == "." || part == ".." {
			return "", false, fmt.Errorf("embedded JDK contains unsafe path %q", name)
		}
	}
	return clean, directory, nil
}

// extractFile 将条目流式写入私有目录并校验声明长度，避免归档元数据掩盖截断内容或 ZIP 炸弹。
func extractFile(destination, relative string, file *zip.File) error {
	path := filepath.Join(destination, filepath.FromSlash(relative))
	if !withinDestination(destination, path) {
		return fmt.Errorf("embedded JDK path escapes extraction root: %q", relative)
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return fmt.Errorf("create JDK parent directory: %w", err)
	}
	mode := os.FileMode(0o600)
	if file.Mode()&0o111 != 0 && runtime.GOOS != "windows" {
		mode = 0o700
	}
	output, err := os.OpenFile(path, os.O_CREATE|os.O_EXCL|os.O_WRONLY, mode)
	if err != nil {
		return fmt.Errorf("create embedded JDK file: %w", err)
	}
	input, err := file.Open()
	if err != nil {
		_ = output.Close()
		return fmt.Errorf("open embedded JDK file: %w", err)
	}
	written, copyErr := io.Copy(output, io.LimitReader(input, int64(file.UncompressedSize64)+1))
	inputErr := input.Close()
	closeErr := output.Close()
	if copyErr != nil {
		return fmt.Errorf("extract embedded JDK file: %w", copyErr)
	}
	if inputErr != nil {
		return fmt.Errorf("verify embedded JDK file: %w", inputErr)
	}
	if closeErr != nil {
		return fmt.Errorf("close embedded JDK file: %w", closeErr)
	}
	if written < 0 || uint64(written) != file.UncompressedSize64 {
		return errors.New("embedded JDK entry size does not match its archive metadata")
	}
	return nil
}

// withinDestination 在平台路径清理后再次验证最终路径，不仅依赖 ZIP 使用斜杠的原始写法。
func withinDestination(root, path string) bool {
	relative, err := filepath.Rel(root, path)
	return err == nil && relative != ".." && !strings.HasPrefix(relative, ".."+string(filepath.Separator)) && !filepath.IsAbs(relative)
}

// findJava 支持普通 JDK 根目录和 macOS 包中的 Contents/Home，并要求发行元数据同时匹配启动器目标和应用字节码版本。
func findJava(destination string, minimumJava int) (string, error) {
	candidates := []string{
		filepath.Join(destination, "Contents", "Home"),
		filepath.Join(destination, "Home"),
		destination,
	}
	for _, root := range candidates {
		releasePath := filepath.Join(root, "release")
		release, err := os.ReadFile(releasePath)
		if err != nil || len(release) > maxReleaseFileSize {
			continue
		}
		attributes, err := parseRelease(release)
		if err != nil {
			return "", err
		}
		if err := validateTarget(attributes); err != nil {
			return "", err
		}
		if err := validateJavaFeature(attributes["JAVA_VERSION"], minimumJava); err != nil {
			return "", err
		}
		binary := "java"
		if runtime.GOOS == "windows" {
			binary += ".exe"
		}
		path := filepath.Join(root, "bin", binary)
		info, err := os.Stat(path)
		if err != nil || !info.Mode().IsRegular() {
			return "", errors.New("embedded JDK java executable is missing")
		}
		if runtime.GOOS != "windows" && info.Mode()&0o111 == 0 {
			return "", errors.New("embedded JDK java executable is not executable")
		}
		if err := validateJavaBinary(path); err != nil {
			return "", err
		}
		return path, nil
	}
	return "", errors.New("embedded JDK release metadata is missing")
}

// validateJavaFeature 将厂商发行版本与应用字节码需求比较；平台正确的可执行文件仍可能因虚拟机过旧而无法运行。
func validateJavaFeature(version string, minimumJava int) error {
	match := javaVersionPattern.FindStringSubmatch(version)
	if len(match) != 2 {
		return errors.New("embedded JDK JAVA_VERSION is not a supported feature-version string")
	}
	feature, err := strconv.Atoi(match[1])
	if err != nil || feature < 1 || feature > 100 {
		return errors.New("embedded JDK JAVA_VERSION feature number is invalid")
	}
	if feature < minimumJava {
		return fmt.Errorf("embedded JDK feature version %d is below archive requirement %d", feature, minimumJava)
	}
	return nil
}

// validateJavaBinary 检查可执行文件格式和架构，因为发行元数据本身不能证明打包的 Java 二进制与启动器匹配。
func validateJavaBinary(binaryPath string) error {
	file, err := os.Open(binaryPath)
	if err != nil {
		return fmt.Errorf("open embedded Java executable: %w", err)
	}
	defer file.Close()
	wantArch := runtime.GOARCH
	switch runtime.GOOS {
	case "windows":
		image, err := pe.NewFile(file)
		if err != nil {
			return fmt.Errorf("embedded Java executable is not a valid PE file: %w", err)
		}
		defer image.Close()
		if !peMachineMatches(image.Machine, wantArch) {
			return fmt.Errorf("embedded Java PE architecture does not match launcher %s", wantArch)
		}
	case "linux":
		image, err := elf.NewFile(file)
		if err != nil {
			return fmt.Errorf("embedded Java executable is not a valid ELF file: %w", err)
		}
		defer image.Close()
		if !elfMachineMatches(image.Machine, wantArch) {
			return fmt.Errorf("embedded Java ELF architecture does not match launcher %s", wantArch)
		}
	case "darwin":
		image, err := macho.NewFile(file)
		if err == nil {
			defer image.Close()
			if !machoMachineMatches(image.Cpu, wantArch) {
				return fmt.Errorf("embedded Java Mach-O architecture does not match launcher %s", wantArch)
			}
			return nil
		}
		fatImage, fatErr := macho.NewFatFile(file)
		if fatErr != nil {
			return fmt.Errorf("embedded Java executable is not a valid Mach-O file: %w", err)
		}
		defer fatImage.Close()
		for _, architecture := range fatImage.Arches {
			if machoMachineMatches(architecture.Cpu, wantArch) {
				return nil
			}
		}
		return fmt.Errorf("embedded Java universal binary has no %s slice", wantArch)
	default:
		return fmt.Errorf("embedded Java executable validation is unsupported on %s", runtime.GOOS)
	}
	return nil
}

// peMachineMatches 将支持的 Windows 目标映射为 PE machine 标识。
func peMachineMatches(machine uint16, architecture string) bool {
	switch architecture {
	case "amd64":
		return machine == pe.IMAGE_FILE_MACHINE_AMD64
	case "arm64":
		return machine == pe.IMAGE_FILE_MACHINE_ARM64
	default:
		return false
	}
}

// elfMachineMatches 将支持的 Linux 目标映射为 ELF machine 标识。
func elfMachineMatches(machine elf.Machine, architecture string) bool {
	switch architecture {
	case "amd64":
		return machine == elf.EM_X86_64
	case "arm64":
		return machine == elf.EM_AARCH64
	default:
		return false
	}
}

// machoMachineMatches 将支持的 macOS 目标映射为 Mach-O CPU 标识。
func machoMachineMatches(machine macho.Cpu, architecture string) bool {
	switch architecture {
	case "amd64":
		return machine == macho.CpuAmd64
	case "arm64":
		return machine == macho.CpuArm64
	default:
		return false
	}
}

// parseRelease 提取平台身份和运行时特性版本；忽略无关厂商字段，以兼容后续 JDK 增加元数据。
func parseRelease(data []byte) (map[string]string, error) {
	result := make(map[string]string, 3)
	for _, line := range strings.Split(string(data), "\n") {
		key, value, ok := strings.Cut(strings.TrimSpace(line), "=")
		if !ok || (key != "OS_NAME" && key != "OS_ARCH" && key != "JAVA_VERSION") {
			continue
		}
		value = strings.Trim(value, `"`)
		if value == "" {
			return nil, fmt.Errorf("embedded JDK %s is empty", key)
		}
		result[key] = value
	}
	if result["OS_NAME"] == "" || result["OS_ARCH"] == "" || result["JAVA_VERSION"] == "" {
		return nil, errors.New("embedded JDK release metadata lacks OS_NAME, OS_ARCH, or JAVA_VERSION")
	}
	return result, nil
}

// validateTarget 将发行元数据与启动器构建目标比对，避免平台错误但格式有效的 JDK 最终仅表现为难诊断的进程错误。
func validateTarget(attributes map[string]string) error {
	name := strings.ToLower(attributes["OS_NAME"])
	arch := normalizeArch(attributes["OS_ARCH"])
	wantName := map[string]string{"windows": "windows", "linux": "linux", "darwin": "mac"}[runtime.GOOS]
	wantArch := normalizeArch(runtime.GOARCH)
	if wantName == "" || arch != wantArch || !strings.Contains(name, wantName) {
		return fmt.Errorf("embedded JDK target %s/%s does not match launcher target %s/%s", attributes["OS_NAME"], attributes["OS_ARCH"], runtime.GOOS, runtime.GOARCH)
	}
	return nil
}

// normalizeArch 将常见厂商架构名称映射为 Go 架构名称，使 Windows、Linux 和 macOS 的发行文件可一致比较。
func normalizeArch(value string) string {
	switch strings.ToLower(value) {
	case "amd64", "x86_64", "x64":
		return "amd64"
	case "arm64", "aarch64":
		return "arm64"
	case "386", "i386", "i486", "i586", "i686", "x86":
		return "386"
	default:
		if _, err := strconv.Atoi(value); err == nil {
			return value
		}
		return strings.ToLower(value)
	}
}
