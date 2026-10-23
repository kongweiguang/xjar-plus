// @author kongweiguang
package license

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"errors"
	"fmt"
	"time"
	"unicode/utf8"
)

const (
	magic        = "XJL2"
	nonceSize    = 12
	keySize      = 32
	maxAppIDSize = 256
	maxFileSize  = 4096
)

var licenseAAD = []byte("xjar-plus/license/v2")

// Grant 表示 key.x 中经认证的有效期；使用 Unix 秒使 Java 与 Go 的比较不受本地时区和夏令时影响。
type Grant struct {
	AppID         string
	NotBeforeUnix int64
	NotAfterUnix  int64
	Perpetual     bool
}

// Parse 认证并校验完整 XJL2 信封，确保损坏或属于其他应用的许可证不会被监督器当作有效授权。
func Parse(data, key []byte, expectedAppID string) (Grant, error) {
	if len(data) < len(magic)+nonceSize+16 || len(data) > maxFileSize {
		return Grant{}, errors.New("license size is invalid")
	}
	if len(key) != keySize {
		return Grant{}, errors.New("license key must be 32 bytes")
	}
	if string(data[:len(magic)]) != magic {
		return Grant{}, errors.New("license magic is invalid")
	}
	cipherBlock, err := aes.NewCipher(key)
	if err != nil {
		return Grant{}, fmt.Errorf("initialize AES-GCM: %w", err)
	}
	gcm, err := cipher.NewGCM(cipherBlock)
	if err != nil {
		return Grant{}, fmt.Errorf("initialize AES-GCM: %w", err)
	}
	nonceStart := len(magic)
	ciphertextStart := nonceStart + nonceSize
	plain, err := gcm.Open(nil, data[nonceStart:ciphertextStart], data[ciphertextStart:], licenseAAD)
	if err != nil {
		return Grant{}, errors.New("license authentication failed")
	}
	grant, err := parsePlaintext(plain)
	if err != nil {
		return Grant{}, err
	}
	if grant.AppID != expectedAppID {
		return Grant{}, errors.New("license belongs to a different appId")
	}
	return grant, nil
}

// parsePlaintext 要求长度完全匹配，避免被忽略的尾随字节成为未认证的许可证语义扩展。
func parsePlaintext(plain []byte) (Grant, error) {
	if len(plain) < 4+16 {
		return Grant{}, errors.New("license plaintext is truncated")
	}
	nameSize := int(binary.BigEndian.Uint32(plain[:4]))
	if nameSize < 1 || nameSize > maxAppIDSize || len(plain) != 4+nameSize+16 {
		return Grant{}, errors.New("license appId length is invalid")
	}
	appIDBytes := plain[4 : 4+nameSize]
	if !utf8.Valid(appIDBytes) {
		return Grant{}, errors.New("license appId encoding is invalid")
	}
	window := plain[4+nameSize:]
	notBefore := int64(binary.BigEndian.Uint64(window[:8]))
	notAfter := int64(binary.BigEndian.Uint64(window[8:]))
	if notBefore < 0 || (notAfter != 0 && notAfter <= notBefore) {
		return Grant{}, errors.New("license validity window is invalid")
	}
	return Grant{AppID: string(appIDBytes), NotBeforeUnix: notBefore, NotAfterUnix: notAfter, Perpetual: notAfter == 0}, nil
}

// Evaluate 按 UTC Unix 秒比较授权；签发协议以秒序列化，不能让本地亚秒精度造成 Java 与 Go 判定不一致。
func (grant Grant) Evaluate(now time.Time) (bool, string, time.Duration) {
	nowUnix := now.UTC().Unix()
	if nowUnix < grant.NotBeforeUnix {
		return false, "application is not valid yet", 0
	}
	if !grant.Perpetual && nowUnix >= grant.NotAfterUnix {
		return false, "application has expired", 0
	}
	if grant.Perpetual {
		return true, "", 0
	}
	remaining := time.Unix(grant.NotAfterUnix, 0).Sub(now.UTC())
	if remaining < 0 {
		remaining = 0
	}
	return true, "", remaining
}
