package main

import (
	"archive/zip"
	"bytes"
	"embed"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

//go:embed resource/*
var resource embed.FS

func main() {
	args := "#{jarArgs}"
	tempDir := os.TempDir()

	jdkFile, err := resource.ReadFile("resource/jdk.zip")
	if err != nil {
		panic(err)
	}

	jdkPath := filepath.Join(tempDir, "app", "jdk")

	err = os.RemoveAll(jdkPath)
	if err != nil {
		panic(err)
	}

	err = UnzipFromBytes(jdkFile, jdkPath)

	if err != nil {
		panic(err)
	}

	appJar, err := resource.ReadFile("resource/#{appName}")
	if err != nil {
		panic(err)
	}

	err = os.WriteFile(jdkPath+"/bin/app.jar", appJar, os.ModePerm)
	if err != nil {
		panic(err)
	}

    args = args + " -jar app.jar"
	cmd := exec.Command("java", strings.Fields(args)...)
	cmd.Dir = filepath.Join(jdkPath, "bin")

	key := bytes.Join([][]byte{
		xKey.algorithm, {13, 10},
		xKey.keysize, {13, 10},
		xKey.ivsize, {13, 10},
		xKey.password, {13, 10},
	}, []byte{})
	cmd.Stdin = bytes.NewReader(key)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	err = cmd.Run()
	if err != nil {
		panic(err)
	}
}

func UnzipFromBytes(zipData []byte, destDir string) error {
	buf := bytes.NewReader(zipData)
	zipReader, err := zip.NewReader(buf, buf.Size())
	if err != nil {
		return fmt.Errorf("创建ZIP读取器失败: %w", err)
	}

	// 创建目标目录
	if err := os.MkdirAll(destDir, os.ModePerm); err != nil {
		return fmt.Errorf("创建目标目录失败: %w", err)
	}

	for _, f := range zipReader.File {
		// 安全检查：防止路径遍历攻击
		if strings.Contains(f.Name, "..") {
			return fmt.Errorf("文件路径不合法: %s", f.Name)
		}

		// 构建目标文件路径
		targetPath := filepath.Join(destDir, f.Name)

		if f.FileInfo().IsDir() {
			// 创建目录
			if err := os.MkdirAll(targetPath, f.Mode()); err != nil {
				return fmt.Errorf("创建目录失败: %w", err)
			}
		} else {
			// 创建文件
			if err := os.MkdirAll(filepath.Dir(targetPath), os.ModePerm); err != nil {
				return fmt.Errorf("创建父目录失败: %w", err)
			}

			// 解压文件
			outFile, err := os.OpenFile(targetPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, f.Mode())
			if err != nil {
				return fmt.Errorf("创建文件失败: %w", err)
			}
			defer outFile.Close()

			inFile, err := f.Open()
			if err != nil {
				return fmt.Errorf("读取ZIP文件失败: %w", err)
			}
			defer inFile.Close()

			if _, err := io.Copy(outFile, inFile); err != nil {
				return fmt.Errorf("写入文件失败: %w", err)
			}

		}
	}

	return nil
}

type XKey struct {
	algorithm []byte
	keysize   []byte
	ivsize    []byte
	password  []byte
}

var xKey = XKey{
	algorithm: []byte{#{xKey.algorithm}},
	keysize:   []byte{#{xKey.keysize}},
	ivsize:    []byte{#{xKey.ivsize}},
	password:  []byte{#{xKey.password}},
}
