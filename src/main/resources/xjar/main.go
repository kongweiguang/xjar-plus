package main

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"embed"
	"encoding/hex"
	"encoding/json"
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
	hexKey         = "#{hexKey}"
	hexIV          = "#{hexIV}"
	code           = "#{code}"
)

//go:embed resource/*
var resource embed.FS

func main() {
	license, err := readLicense()
	if err != nil {
		warnf("license file is unavailable or invalid")
	}

	duration, extArgs, err := checkDate(license)
	if err != nil {
		exitWithErr("E_VALIDITY", "license validity check failed", err)
	}

	jdkPath := filepath.Join(os.TempDir(), "deploy", tempDirName(code), "jdk")

	if err := preEnv(jdkPath); err != nil {
		exitWithErr("E_RUNTIME", "runtime environment initialization failed", err)
	}

	if err := runApp(jdkPath, extArgs, duration); err != nil {
		exitWithErr("E_APP", "application startup failed", err)
	}
}

func readLicense() (*License, error) {
	exePath, err := os.Executable()
	if err != nil {
		return nil, fmt.Errorf("locate executable: %w", err)
	}
	licensePath := filepath.Join(filepath.Dir(exePath), "key.x")
	cipherData, err := os.ReadFile(licensePath)

	if err != nil {
		return nil, fmt.Errorf("read license file: %w", err)
	}

	key, err := hex.DecodeString(hexKey)
	if err != nil {
		return nil, fmt.Errorf("decode license key: %w", err)
	}
	iv, err := hex.DecodeString(hexIV)
	if err != nil {
		return nil, fmt.Errorf("decode license iv: %w", err)
	}
	plain, err := decryptAesCbc(cipherData, key, iv)

	if err != nil {
		return nil, fmt.Errorf("decrypt license file: %w", err)
	}

	var lcs License
	err = json.Unmarshal(plain, &lcs)

	if err != nil {
		return nil, fmt.Errorf("parse license file: %w", err)
	}

	if code != lcs.Code {
		return nil, fmt.Errorf("license is not valid for this application")
	}

	return &lcs, nil
}

func preEnv(jdkPath string) error {
	if err := os.RemoveAll(jdkPath); err != nil {
		return fmt.Errorf("clean runtime directory: %w", err)
	}

	jdkZip, err := resource.ReadFile(jdkZipPath)
	if err != nil {
		return fmt.Errorf("read embedded runtime: %w", err)
	}

	if err := unzip4Bytes(jdkZip, jdkPath); err != nil {
		return fmt.Errorf("extract runtime: %w", err)
	}

	if err := chmodJdkCommands(jdkPath); err != nil {
		return fmt.Errorf("chmod jdk commands: %w", err)
	}

	appJar, err := resource.ReadFile(jarPath)
	if err != nil {
		return fmt.Errorf("read embedded application: %w", err)
	}

	appPath := filepath.Join(jdkPath, "bin", "app.jar")
	if err := os.WriteFile(appPath, appJar, 0644); err != nil {
		return fmt.Errorf("write application file: %w", err)
	}

	return nil
}

func tempDirName(value string) string {
	name := hex.EncodeToString([]byte(value))
	if len(name) > 10 {
		return name[:10]
	}
	return name
}

func chmodJdkCommands(jdkPath string) error {
	binPath := filepath.Join(jdkPath, "bin")

	return filepath.Walk(binPath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}

		if info.IsDir() {
			return nil
		}

		if err := os.Chmod(path, 0755); err != nil {
			return fmt.Errorf("chmod jdk command: %w", err)
		}

		return nil
	})
}

func runApp(jdkPath, extArgs string, duration time.Duration) error {
	args, err := parseArgs(strings.Join([]string{"#{jarArgs}", extArgs}, " "))
	if err != nil {
		return fmt.Errorf("parse java args: %w", err)
	}
	args = append(args, "-jar", "app.jar")

	ctx := context.Background()
	var cancel context.CancelFunc
	if duration > 0 {
		ctx, cancel = context.WithTimeout(ctx, duration)
		defer cancel()
	}

	javaPath := filepath.Join(jdkPath, "bin", "java")
	cmd := exec.CommandContext(ctx, javaPath, args...)
	cmd.Dir = filepath.Join(jdkPath, "bin")
	cmd.Stdin = bytes.NewReader(encodeKey())
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	err = cmd.Run()
	if ctx.Err() == context.DeadlineExceeded {
		return fmt.Errorf("process timed out after %s", duration)
	}
	if err == nil {
		return nil
	}
	if exitErr, ok := err.(*exec.ExitError); ok {
		return fmt.Errorf("application process exited with code %d", exitErr.ExitCode())
	}
	return fmt.Errorf("start application process: %w", err)
}

func parseArgs(input string) ([]string, error) {
	var args []string
	var current strings.Builder
	var quote rune
	pendingBackslash := false
	tokenStarted := false

	for _, r := range input {
		switch {
		case pendingBackslash:
			if r != quote && r != '\\' {
				current.WriteRune('\\')
			}
			current.WriteRune(r)
			pendingBackslash = false
		case quote != 0 && r == '\\':
			pendingBackslash = true
			tokenStarted = true
		case quote != 0:
			if r == quote {
				quote = 0
			} else {
				current.WriteRune(r)
			}
			tokenStarted = true
		case r == '\'' || r == '"':
			quote = r
			tokenStarted = true
		case r == ' ' || r == '\t' || r == '\r' || r == '\n':
			if tokenStarted {
				args = append(args, current.String())
				current.Reset()
				tokenStarted = false
			}
		default:
			current.WriteRune(r)
			tokenStarted = true
		}
	}

	if pendingBackslash {
		current.WriteRune('\\')
	}
	if quote != 0 {
		return nil, fmt.Errorf("invalid args: unclosed quote")
	}
	if tokenStarted {
		args = append(args, current.String())
	}

	return args, nil
}

func encodeKey() []byte {
	return bytes.Join([][]byte{
		xKey.algorithm, {13, 10},
		xKey.keysize, {13, 10},
		xKey.ivsize, {13, 10},
		xKey.password, {13, 10},
	}, []byte{})
}

func checkDate(lcs *License) (time.Duration, string, error) {
	vsd := ""
	ved := ""
	args := ""

	if lcs == nil {
		vsd = validStartDate
		ved = validEndDate
	} else {
		vsd = lcs.ValidStartDate
		ved = lcs.ValidEndDate
		args = lcs.Args
	}

	now := time.Now()
	loc := time.Local

	start, err := time.ParseInLocation(dateFormat, vsd, loc)
	if err != nil {
		return -1, args, fmt.Errorf("invalid start date")
	}

	if now.Before(start) {
		return -1, args, fmt.Errorf("application is not valid yet")
	}

	if ved == "" {
		return -1, args, nil
	}

	end, err := time.ParseInLocation(dateFormat, ved, loc)
	if err != nil {
		return -1, args, fmt.Errorf("invalid end date")
	}

	if now.After(end) {
		return -1, args, fmt.Errorf("application has expired")
	}

	return end.Sub(now), args, nil
}

func unPKCS5Padding(data []byte) ([]byte, error) {
	if len(data) == 0 {
		return nil, fmt.Errorf("input is empty")
	}

	padLen := int(data[len(data)-1])
	if padLen <= 0 || padLen > len(data) {
		return nil, fmt.Errorf("invalid padding size")
	}

	for _, v := range data[len(data)-padLen:] {
		if int(v) != padLen {
			return nil, fmt.Errorf("invalid padding bytes")
		}
	}

	return data[:len(data)-padLen], nil
}

func decryptAesCbc(cipherData, key, iv []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, fmt.Errorf("create cipher: %w", err)
	}

	if len(iv) != block.BlockSize() {
		return nil, fmt.Errorf("invalid iv size")
	}

	if len(cipherData)%block.BlockSize() != 0 {
		return nil, fmt.Errorf("ciphertext length is not a multiple of block size")
	}

	mode := cipher.NewCBCDecrypter(block, iv)
	plain := make([]byte, len(cipherData))
	mode.CryptBlocks(plain, cipherData)

	return unPKCS5Padding(plain)
}

func unzip4Bytes(data []byte, dest string) error {
	reader := bytes.NewReader(data)
	zr, err := zip.NewReader(reader, int64(len(data)))
	if err != nil {
		return fmt.Errorf("create zip reader: %w", err)
	}

	cleanDest, err := filepath.Abs(dest)
	if err != nil {
		return fmt.Errorf("resolve destination: %w", err)
	}

	for _, f := range zr.File {
		if filepath.IsAbs(f.Name) {
			return fmt.Errorf("invalid file path: %s", f.Name)
		}

		target := filepath.Join(cleanDest, f.Name)
		cleanTarget, err := filepath.Abs(target)
		if err != nil {
			return fmt.Errorf("resolve runtime file: %w", err)
		}
		rel, err := filepath.Rel(cleanDest, cleanTarget)
		if err != nil {
			return fmt.Errorf("check runtime file: %w", err)
		}
		if rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
			return fmt.Errorf("invalid runtime file path")
		}

		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, os.ModePerm); err != nil {
				return fmt.Errorf("create runtime directory: %w", err)
			}
			continue
		}

		if err := os.MkdirAll(filepath.Dir(target), os.ModePerm); err != nil {
			return fmt.Errorf("create runtime parent directory: %w", err)
		}

		src, err := f.Open()
		if err != nil {
			return fmt.Errorf("open runtime archive entry: %w", err)
		}

		dst, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, f.Mode())
		if err != nil {
			if closeErr := src.Close(); closeErr != nil {
				return fmt.Errorf("close runtime archive entry after create failed: %w", closeErr)
			}
			return fmt.Errorf("create runtime file: %w", err)
		}

		if _, err := io.Copy(dst, src); err != nil {
			if closeErr := src.Close(); closeErr != nil {
				return fmt.Errorf("close runtime archive entry after copy failed: %w", closeErr)
			}
			if closeErr := dst.Close(); closeErr != nil {
				return fmt.Errorf("close runtime file after copy failed: %w", closeErr)
			}
			return fmt.Errorf("copy runtime file: %w", err)
		}

		err = src.Close()
		if err != nil {
			return fmt.Errorf("close runtime archive entry: %w", err)
		}

		err = dst.Close()
		if err != nil {
			return fmt.Errorf("close runtime file: %w", err)
		}
	}

	return nil
}

func warnf(format string, args ...interface{}) {
	fmt.Fprintf(os.Stderr, "warning: "+format+"\n", args...)
}

func exitWithErr(code, message string, err error) {
	fmt.Fprintf(os.Stderr, "%s: %s", code, message)
	if err != nil {
		fmt.Fprintf(os.Stderr, ": %s", publicErr(err))
	}
	fmt.Fprintln(os.Stderr)
	os.Exit(1)
}

func publicErr(err error) string {
	if err == nil {
		return ""
	}
	msg := err.Error()
	if strings.Contains(msg, string(os.PathSeparator)) || strings.Contains(msg, ":\\") || strings.Contains(msg, "/") {
		return "please check the local runtime files and permissions"
	}
	return msg
}

type XKey struct {
	algorithm []byte
	keysize   []byte
	ivsize    []byte
	password  []byte
}

type License struct {
	Code           string `json:"code"`
	ValidStartDate string `json:"validStartDate"`
	ValidEndDate   string `json:"validEndDate"`
	Args           string `json:"args"`
}

var xKey = XKey{
	algorithm: []byte{#{xKey.algorithm}},
	keysize:   []byte{#{xKey.keysize}},
	ivsize:    []byte{#{xKey.ivsize}},
	password:  []byte{#{xKey.password}},
}
