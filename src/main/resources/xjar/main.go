package main

import (
	"archive/zip"
	"bytes"
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
	duration, err := checkDate()
	if err != nil {
		exitWithMsg("license expired")
	}

	jdkPath := filepath.Join(os.TempDir(), "deploy", hex.EncodeToString([]byte(code))[:10], "jdk")

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
	exePath, _ := os.Executable()
	cipherData, err := os.ReadFile(filepath.Join(filepath.Dir(exePath), "key.x"))
	vsd := ""
	ved := ""

	if err != nil {
		fmt.Println("failed to read key.x -> use app key info ")
		vsd = validStartDate
		ved = validEndDate
	} else {
		key, _ := hex.DecodeString(hexKey)
		iv, _ := hex.DecodeString(hexIV)
		plain, err := decryptAesCbc(cipherData, key, iv)

		if err != nil {
			fmt.Println("license error")
		}

		var lcs License
		err = json.Unmarshal(plain, &lcs)
		if err != nil {
			return -1, fmt.Errorf("license error")
		}

		if code != lcs.Code {
			return -1, fmt.Errorf("license error")
		}

		vsd = lcs.ValidStartDate
		ved = lcs.ValidEndDate
	}

	now := time.Now()
	loc := time.Local

	start, err := time.ParseInLocation(dateFormat, vsd, loc)
	if err != nil {
		return -1, fmt.Errorf("date parse error")
	}

	if now.Before(start) {
		return -1, fmt.Errorf("date expired")
	}

	if ved == "" {
		return -1, nil
	}

	end, err := time.ParseInLocation(dateFormat, ved, loc)
	if err != nil {
		return -1, fmt.Errorf("date parse error")
	}

	if now.After(end) {
		return -1, fmt.Errorf("date expired")
	}

	return end.Sub(now), nil
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
		return nil, fmt.Errorf("failed to create cipher")
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

type License struct {
	Code           string `json:"code"`
	ValidStartDate string `json:"validStartDate"`
	ValidEndDate   string `json:"validEndDate"`
}

var xKey = XKey{
	algorithm: []byte{#{xKey.algorithm}},
	keysize:   []byte{#{xKey.keysize}},
	ivsize:    []byte{#{xKey.ivsize}},
	password:  []byte{#{xKey.password}},
}
