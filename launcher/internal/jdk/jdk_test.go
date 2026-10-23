// @author kongweiguang
package jdk

import (
	"archive/zip"
	"bytes"
	"encoding/binary"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// TestExtractAcceptsTargetReleaseLayout 验证常见单根厂商归档布局，并确认安全解压后仍保留可执行权限。
func TestExtractAcceptsTargetReleaseLayout(t *testing.T) {
	archive := testJDKZip(t, map[string][]byte{
		"jdk/release":            []byte(fmt.Sprintf("OS_NAME=\"%s\"\nOS_ARCH=\"%s\"\nJAVA_VERSION=\"21.0.2\"\n", releaseOS(), releaseArch())),
		"jdk/bin/" + javaName():  testJavaBinary(t),
		"jdk/lib/runtime.marker": []byte("runtime"),
	})
	destination := filepath.Join(t.TempDir(), "jdk")
	javaPath, err := Extract(destination, archive, 8)
	if err != nil {
		t.Fatalf("Extract() error = %v", err)
	}
	if javaPath != filepath.Join(destination, "bin", javaName()) {
		t.Fatalf("java path = %q", javaPath)
	}
	info, err := os.Stat(javaPath)
	if err != nil {
		t.Fatal(err)
	}
	if runtime.GOOS != "windows" && info.Mode()&0o111 == 0 {
		t.Fatalf("java mode = %v, expected executable", info.Mode())
	}
}

// TestExtractRejectsTraversalAndWrongTarget 验证恶意路径在写入前被拒绝，且其他平台的有效归档不会进入执行阶段。
func TestExtractRejectsTraversalAndWrongTarget(t *testing.T) {
	traversal := testJDKZip(t, map[string][]byte{"../escape": []byte("bad")})
	if _, err := Extract(t.TempDir(), traversal, 8); err == nil {
		t.Fatal("Extract() accepted a traversal path")
	}
	wrongTarget := "Linux"
	if runtime.GOOS == "linux" {
		wrongTarget = "Windows"
	}
	archive := testJDKZip(t, map[string][]byte{
		"release":           []byte(fmt.Sprintf("OS_NAME=\"%s\"\nOS_ARCH=\"%s\"\nJAVA_VERSION=\"21.0.2\"\n", wrongTarget, releaseArch())),
		"bin/" + javaName(): []byte("test-runtime"),
	})
	if _, err := Extract(filepath.Join(t.TempDir(), "jdk"), archive, 8); err == nil {
		t.Fatal("Extract() accepted a JDK for another operating system")
	}
}

// TestExtractSupportsMacContentsHome 选择 macOS 包内的运行时目录，避免把包元数据误认作 JDK 根目录。
func TestExtractSupportsMacContentsHome(t *testing.T) {
	if runtime.GOOS != "darwin" {
		t.Skip("macOS bundle layout is only selected on darwin")
	}
	archive := testJDKZip(t, map[string][]byte{
		"jdk/Contents/Home/release":           []byte("OS_NAME=\"Mac OS X\"\nOS_ARCH=\"" + releaseArch() + "\"\nJAVA_VERSION=\"21.0.2\"\n"),
		"jdk/Contents/Home/bin/" + javaName(): testJavaBinary(t),
	})
	javaPath, err := Extract(filepath.Join(t.TempDir(), "jdk"), archive, 8)
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(filepath.Dir(filepath.Dir(javaPath))) != "Home" {
		t.Fatalf("macOS java path = %q", javaPath)
	}
}

// TestExtractSupportsSafeSymlinkChains 覆盖 Unix JDK 常见安全别名链，并确保校验结果不依赖 ZIP 条目顺序。
func TestExtractSupportsSafeSymlinkChains(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows JDK archives do not use POSIX symbolic links")
	}
	archive := testJDKZipEntries(t, []testZipEntry{
		{name: "jdk/bin/java", content: []byte("java-link"), mode: os.ModeSymlink | 0o777},
		{name: "jdk/release", content: []byte(fmt.Sprintf("OS_NAME=\"%s\"\nOS_ARCH=\"%s\"\nJAVA_VERSION=\"21.0.2\"\n", releaseOS(), releaseArch())), mode: 0o644},
		{name: "jdk/bin/java-link", content: []byte("java-real"), mode: os.ModeSymlink | 0o777},
		{name: "jdk/bin/java-real", content: testJavaBinary(t), mode: 0o755},
	})
	destination := filepath.Join(t.TempDir(), "jdk")
	javaPath, err := Extract(destination, archive, 8)
	if err != nil {
		t.Fatalf("Extract() rejected safe symlink chain: %v", err)
	}
	if javaPath != filepath.Join(destination, "bin", "java") {
		t.Fatalf("java path = %q", javaPath)
	}
	if info, err := os.Stat(javaPath); err != nil || !info.Mode().IsRegular() {
		t.Fatalf("resolved java path is not a regular file: info=%v err=%v", info, err)
	}
}

// TestExtractSupportsSafeDirectorySymlink 验证嵌套文件通过根内目录别名访问，而不是复制到脱离原位置的占位目录树。
func TestExtractSupportsSafeDirectorySymlink(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows JDK archives do not use POSIX symbolic links")
	}
	archive := testJDKZipEntries(t, []testZipEntry{
		{name: "jdk/release", content: []byte(fmt.Sprintf("OS_NAME=\"%s\"\nOS_ARCH=\"%s\"\nJAVA_VERSION=\"21.0.2\"\n", releaseOS(), releaseArch())), mode: 0o644},
		{name: "jdk/bin/java", content: testJavaBinary(t), mode: 0o755},
		{name: "jdk/lib/current", content: []byte("versions/v1"), mode: os.ModeSymlink | 0o777},
		{name: "jdk/lib/versions/v1/modules/marker", content: []byte("inside"), mode: 0o644},
	})
	destination := filepath.Join(t.TempDir(), "jdk")
	if _, err := Extract(destination, archive, 8); err != nil {
		t.Fatalf("Extract() rejected safe directory alias: %v", err)
	}
	content, err := os.ReadFile(filepath.Join(destination, "lib", "current", "modules", "marker"))
	if err != nil || string(content) != "inside" {
		t.Fatalf("directory alias content = %q, %v", content, err)
	}
}

// testJavaBinary 复用当前主机测试可执行文件，使二进制格式校验面对真实目标架构映像而非仅含魔数的样本。
func testJavaBinary(t *testing.T) []byte {
	t.Helper()
	path, err := os.Executable()
	if err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return data
}

// TestExtractRejectsUnsafeSymlinks 验证路径穿越、目标缺失、循环和过长链接链会在创建链接前被拒绝。
func TestExtractRejectsUnsafeSymlinks(t *testing.T) {
	if runtime.GOOS == "windows" {
		archive := testJDKZipEntries(t, []testZipEntry{{name: "jdk/bin/java", content: []byte("java-real"), mode: os.ModeSymlink | 0o777}})
		if _, err := Extract(filepath.Join(t.TempDir(), "jdk"), archive, 8); err == nil {
			t.Fatal("Extract() accepted a ZIP symlink on Windows")
		}
		return
	}
	for name, entries := range map[string][]testZipEntry{
		"escape":  {{name: "jdk/bin/java", content: []byte("../../../outside"), mode: os.ModeSymlink | 0o777}},
		"missing": {{name: "jdk/bin/java", content: []byte("missing"), mode: os.ModeSymlink | 0o777}},
		"cycle": {
			{name: "jdk/bin/java", content: []byte("java-link"), mode: os.ModeSymlink | 0o777},
			{name: "jdk/bin/java-link", content: []byte("java"), mode: os.ModeSymlink | 0o777},
		},
	} {
		t.Run(name, func(t *testing.T) {
			archive := testJDKZipEntries(t, entries)
			if _, err := Extract(filepath.Join(t.TempDir(), "jdk"), archive, 8); err == nil {
				t.Fatalf("Extract() accepted unsafe %s symlink", name)
			}
		})
	}
	depth := []testZipEntry{{name: "jdk/bin/java", content: []byte("link-00"), mode: os.ModeSymlink | 0o777}}
	for index := 0; index < maxLinkDepth; index++ {
		target := fmt.Sprintf("link-%02d", index+1)
		if index == maxLinkDepth-1 {
			target = "java-real"
		}
		depth = append(depth, testZipEntry{
			name:    fmt.Sprintf("jdk/bin/link-%02d", index),
			content: []byte(target),
			mode:    os.ModeSymlink | 0o777,
		})
	}
	depth = append(depth, testZipEntry{name: "jdk/bin/java-real", content: testJavaBinary(t), mode: 0o755})
	if _, err := Extract(filepath.Join(t.TempDir(), "jdk"), testJDKZipEntries(t, depth), 8); err == nil {
		t.Fatal("Extract() accepted a symlink chain over the configured depth limit")
	}
}

// TestExtractRejectsUnsafeZipMetadataBeforeCreatingRoot 确保不支持的 ZIP 特性不会触发文件系统修改或可执行内容读取。
func TestExtractRejectsUnsafeZipMetadataBeforeCreatingRoot(t *testing.T) {
	base := func() []testZipEntry {
		return []testZipEntry{
			{name: "jdk/release", content: []byte(fmt.Sprintf("OS_NAME=\"%s\"\nOS_ARCH=\"%s\"\nJAVA_VERSION=\"21.0.2\"\n", releaseOS(), releaseArch())), mode: 0o644},
			{name: "jdk/bin/" + javaName(), content: testJavaBinary(t), mode: 0o755},
		}
	}
	cases := map[string]func() []byte{
		"encrypted": func() []byte {
			entries := append(base(), testZipEntry{name: "jdk/secret", content: []byte("ciphertext"), mode: 0o600, flags: 1})
			return testJDKZipEntries(t, entries)
		},
		"nonemptyDirectory": func() []byte {
			archive := testJDKZipEntries(t, append(base(), testZipEntry{name: "jdk/nonempty/", mode: os.ModeDir | 0o755}))
			central := bytes.LastIndex(archive, []byte{'P', 'K', 1, 2})
			if central < 0 {
				t.Fatal("test archive lacks a central directory entry")
			}
			binary.LittleEndian.PutUint32(archive[central+24:central+28], 1)
			return archive
		},
		"longPath": func() []byte {
			entries := append(base(), testZipEntry{name: "jdk/" + strings.Repeat("a", 4100), content: []byte("x"), mode: 0o600})
			return testJDKZipEntries(t, entries)
		},
		"unsupportedMethod": func() []byte {
			archive := testJDKZipEntries(t, append(base(), testZipEntry{name: "jdk/other", content: []byte("x"), mode: 0o600}))
			central := bytes.LastIndex(archive, []byte{'P', 'K', 1, 2})
			if central < 0 {
				t.Fatal("test archive lacks a central directory entry")
			}
			binary.LittleEndian.PutUint16(archive[central+10:central+12], 99)
			return archive
		},
	}
	for name, createArchive := range cases {
		t.Run(name, func(t *testing.T) {
			destination := filepath.Join(t.TempDir(), "jdk")
			if _, err := Extract(destination, createArchive(), 8); err == nil {
				t.Fatal("Extract() accepted unsupported ZIP metadata")
			}
			if _, err := os.Lstat(destination); !os.IsNotExist(err) {
				t.Fatalf("Extract() created destination before validation: %v", err)
			}
		})
	}
}

// TestExtractEnforcesApplicationJavaFloor 验证厂商版本解析，并确认其他条件匹配但版本过低的 JDK 在建树前即被拒绝。
func TestExtractEnforcesApplicationJavaFloor(t *testing.T) {
	if err := validateJavaFeature("1.8.0_402", 8); err != nil {
		t.Fatalf("validateJavaFeature() rejected Java 8 syntax: %v", err)
	}
	if err := validateJavaFeature("21.0.2", 22); err == nil {
		t.Fatal("validateJavaFeature() accepted an older JDK")
	}
	archive := testJDKZip(t, map[string][]byte{
		"release":           []byte(fmt.Sprintf("OS_NAME=\"%s\"\nOS_ARCH=\"%s\"\nJAVA_VERSION=\"17.0.1\"\n", releaseOS(), releaseArch())),
		"bin/" + javaName(): testJavaBinary(t),
	})
	destination := filepath.Join(t.TempDir(), "jdk")
	if _, err := Extract(destination, archive, 21); err == nil {
		t.Fatal("Extract() accepted a JDK below minimumJava")
	}
	if _, err := os.Lstat(destination); !os.IsNotExist(err) {
		t.Fatalf("Extract() created destination before version validation: %v", err)
	}
}

type testZipEntry struct {
	name    string
	content []byte
	mode    os.FileMode
	method  uint16
	flags   uint16
}

// testJDKZip 不调用系统 ZIP 工具来创建小型确定性归档夹具，使路径和平台测试不依赖主机工具。
func testJDKZip(t *testing.T, files map[string][]byte) []byte {
	entries := make([]testZipEntry, 0, len(files))
	for name, content := range files {
		entries = append(entries, testZipEntry{name: name, content: content, mode: 0o755})
	}
	return testJDKZipEntries(t, entries)
}

// testJDKZipEntries 构造带明确 POSIX 模式且顺序固定的夹具，使符号链接测试不依赖主机归档工具。
func testJDKZipEntries(t *testing.T, entries []testZipEntry) []byte {
	t.Helper()
	var buffer bytes.Buffer
	writer := zip.NewWriter(&buffer)
	for _, entry := range entries {
		method := entry.method
		if method == 0 {
			method = zip.Deflate
		}
		header := &zip.FileHeader{Name: entry.name, Method: method, Flags: entry.flags}
		header.SetMode(entry.mode)
		zipEntry, err := writer.CreateHeader(header)
		if err != nil {
			t.Fatal(err)
		}
		if len(entry.content) > 0 {
			if _, err := zipEntry.Write(entry.content); err != nil {
				t.Fatal(err)
			}
		}
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return buffer.Bytes()
}

// releaseOS 将当前 Go 目标映射为 JDK 厂商发行文件使用的平台名称。
func releaseOS() string {
	switch runtime.GOOS {
	case "windows":
		return "Windows"
	case "darwin":
		return "Mac OS X"
	default:
		return "Linux"
	}
}

// releaseArch 生成与厂商格式兼容的架构名称，供 JDK 归档夹具使用。
func releaseArch() string {
	switch runtime.GOARCH {
	case "amd64":
		return "x86_64"
	case "arm64":
		return "aarch64"
	default:
		return runtime.GOARCH
	}
}

// javaName 对齐 JDK 解析器使用的目标平台可执行文件后缀，不依赖主机 PATHEXT 或文件系统行为。
func javaName() string {
	if runtime.GOOS == "windows" {
		return "java.exe"
	}
	return "java"
}
