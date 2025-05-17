package main

import (
	"archive/zip"
	"bytes"
	"embed"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

const (
	jdkZipPath     = "resource/jdk.zip"
	jarPath        = "resource/#{appName}"
	dateFormat     = "2006-01-02 15:04:05"
	validStartDate = "#{validStartDate}"
	validEndDate   = "#{validEndDate}"
)

//go:embed resource/*
var resource embed.FS

func main() {
	duration, err := checkDate()
	if err != nil {
		exitWithMsg("license expired")
	}

	jdkPath := filepath.Join(os.TempDir(), "deploy", "jdk")

	if err := preEnv(jdkPath); err != nil {
		exitWithMsg("pre")
	}

	if err := runApp(jdkPath, duration); err != nil {
		exitWithMsg("run")
	}

}

func preEnv(jdkPath string) error {
	if err := os.RemoveAll(jdkPath); err != nil {
		return err
	}

	jdkZip, err := resource.ReadFile(jdkZipPath)
	if err != nil {
		return err
	}

	if err := unzip4Bytes(jdkZip, jdkPath); err != nil {
		return err
	}

	appJar, err := resource.ReadFile(jarPath)
	if err != nil {
		return err
	}

	if err := os.WriteFile(filepath.Join(jdkPath, "bin", "app.jar"), appJar, os.ModePerm); err != nil {
		return err
	}

	return nil
}

func runApp(jdkPath string, duration time.Duration) error {
	args := "#{jarArgs}" + " -jar app.jar"
	cmd := exec.Command(filepath.Join(jdkPath, "bin", "java"), strings.Fields(args)...)
    cmd.Dir = filepath.Join(jdkPath, "bin")
	cmd.Stdin = bytes.NewReader(encodeKey())
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if duration > 0 {
		time.AfterFunc(duration, func() {
			fmt.Println("stop process")
			err := cmd.Process.Kill()
			if err != nil {
				fmt.Println("kill process error")
			}
			os.Exit(666)
		})
	}

	return cmd.Run()
}

func encodeKey() []byte {
	return bytes.Join([][]byte{
		xKey.algorithm, {13, 10},
		xKey.keysize, {13, 10},
		xKey.ivsize, {13, 10},
		xKey.password, {13, 10},
	}, []byte{})
}

func checkDate() (time.Duration, error) {
	now := time.Now()
	loc := time.Local

	start, err := time.ParseInLocation(dateFormat, validStartDate, loc)
	if err != nil {
		return -1, fmt.Errorf("date parse error")
	}

	if now.Before(start) {
		return -1, fmt.Errorf("date expired")
	}

	if validEndDate == "" {
		return -1, nil
	}

	end, err := time.ParseInLocation(dateFormat, validEndDate, loc)
	if err != nil {
		return -1, fmt.Errorf("date parse error")
	}

	if now.After(end) {
		return -1, fmt.Errorf("date expired")
	}

	return end.Sub(now), nil
}

func unzip4Bytes(data []byte, dest string) error {
	reader := bytes.NewReader(data)
	zr, err := zip.NewReader(reader, int64(len(data)))
	if err != nil {
		return fmt.Errorf("create zip reader: %w", err)
	}

	for _, f := range zr.File {
		if strings.Contains(f.Name, "..") {
			return fmt.Errorf("invalid file path: %s", f.Name)
		}

		target := filepath.Join(dest, f.Name)
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, os.ModePerm); err != nil {
				return fmt.Errorf("mkdir: %w", err)
			}
			continue
		}

		if err := os.MkdirAll(filepath.Dir(target), os.ModePerm); err != nil {
			return fmt.Errorf("mkdir parent: %w", err)
		}

		src, err := f.Open()
		if err != nil {
			return fmt.Errorf("open zip file: %w", err)
		}

		dst, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, f.Mode())
		if err != nil {
			err := src.Close()
			if err != nil {
				return err
			}
			return fmt.Errorf("create file: %w", err)
		}

		if _, err := io.Copy(dst, src); err != nil {
			err := src.Close()
			if err != nil {
				return err
			}
			err = dst.Close()
			if err != nil {
				return err
			}
			return fmt.Errorf("copy file: %w", err)
		}

		err = src.Close()
		if err != nil {
			return err
		}

		err = dst.Close()
		if err != nil {
			return err
		}
	}

	return nil
}

func exitWithMsg(msg string) {
	fmt.Println(msg)
	os.Exit(666)
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
