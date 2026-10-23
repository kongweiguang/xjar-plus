// @author kongweiguang
package config

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
	"unicode/utf8"
)

const (
	// MaxConfigBytes 限制内嵌配置大小，避免 JSON 解析消耗无界内存或 CPU。
	MaxConfigBytes = 64 * 1024
	keySize        = 32
	maxAppIDBytes  = 256
	minJavaFloor   = 8
	maxJavaFloor   = 100
)

// Config 仅保存启动器运行所需字段；严格解码可防止新增字段或拼写错误静默削弱打包约束。
type Config struct {
	Version     int      `json:"version"`
	AppID       string   `json:"appId"`
	ArtifactID  string   `json:"artifactId"`
	DataKey     []byte   `json:"dataKey"`
	LicenseKey  []byte   `json:"licenseKey"`
	EmbeddedJDK bool     `json:"embeddedJdk"`
	MinimumJava int      `json:"minimumJava"`
	JVMArgs     []string `json:"jvmArgs"`
	AppArgs     []string `json:"appArgs"`
}

// wireConfig 将 Base64 校验显式化，避免 JSON 解码器对密钥材料接受多种文本编码。
type wireConfig struct {
	Version     int      `json:"version"`
	AppID       string   `json:"appId"`
	ArtifactID  string   `json:"artifactId"`
	DataKey     string   `json:"dataKey"`
	LicenseKey  string   `json:"licenseKey"`
	EmbeddedJDK bool     `json:"embeddedJdk"`
	MinimumJava int      `json:"minimumJava"`
	JVMArgs     []string `json:"jvmArgs"`
	AppArgs     []string `json:"appArgs"`
}

// Parse 在创建进程前拒绝含糊的 JSON 和格式错误的密钥，避免届时为诊断问题而暴露密钥值。
func Parse(data []byte) (Config, error) {
	if len(data) == 0 || len(data) > MaxConfigBytes || !utf8.Valid(data) {
		return Config{}, errors.New("configuration size or encoding is invalid")
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := rejectDuplicateFields(bytes.NewReader(data)); err != nil {
		return Config{}, fmt.Errorf("decode configuration: %w", err)
	}
	var wire wireConfig
	if err := decoder.Decode(&wire); err != nil {
		return Config{}, fmt.Errorf("decode configuration: %w", err)
	}
	if err := decoder.Decode(new(any)); !errors.Is(err, io.EOF) {
		return Config{}, errors.New("configuration contains trailing JSON data")
	}
	dataKey, err := decodeKey(wire.DataKey)
	if err != nil {
		return Config{}, fmt.Errorf("dataKey: %w", err)
	}
	licenseKey, err := decodeKey(wire.LicenseKey)
	if err != nil {
		return Config{}, fmt.Errorf("licenseKey: %w", err)
	}
	cfg := Config{
		Version: wire.Version, AppID: wire.AppID, ArtifactID: wire.ArtifactID,
		DataKey: dataKey, LicenseKey: licenseKey, EmbeddedJDK: wire.EmbeddedJDK,
		MinimumJava: wire.MinimumJava, JVMArgs: wire.JVMArgs, AppArgs: wire.AppArgs,
	}
	if err := cfg.Validate(); err != nil {
		return Config{}, err
	}
	return cfg, nil
}

// Validate 校验归档所需的最低 Java 版本、协议标识和参数，避免不兼容运行时或不完整配置启动业务。
func (cfg Config) Validate() error {
	if cfg.Version != 2 {
		return fmt.Errorf("unsupported configuration version %d", cfg.Version)
	}
	if !utf8.ValidString(cfg.AppID) || len(cfg.AppID) < 1 || len(cfg.AppID) > maxAppIDBytes {
		return errors.New("appId must contain 1 to 256 valid UTF-8 bytes")
	}
	if !validIdentifier(cfg.ArtifactID, 180) {
		return errors.New("artifactId must be a non-empty, whitespace-free identifier of at most 180 bytes")
	}
	if len(cfg.DataKey) != keySize || len(cfg.LicenseKey) != keySize {
		return errors.New("dataKey and licenseKey must each decode to 32 bytes")
	}
	if cfg.MinimumJava < minJavaFloor || cfg.MinimumJava > maxJavaFloor {
		return fmt.Errorf("minimumJava must be between %d and %d", minJavaFloor, maxJavaFloor)
	}
	if cfg.JVMArgs == nil || cfg.AppArgs == nil {
		return errors.New("jvmArgs and appArgs must be JSON arrays")
	}
	if err := validateArgs(cfg.JVMArgs); err != nil {
		return fmt.Errorf("jvmArgs: %w", err)
	}
	if err := validateArgs(cfg.AppArgs); err != nil {
		return fmt.Errorf("appArgs: %w", err)
	}
	return nil
}

// decodeKey 只接受规范的带填充 Base64，避免同一密钥的多种文本形式增加产物比对和诊断歧义。
func decodeKey(value string) ([]byte, error) {
	key, err := base64.StdEncoding.Strict().DecodeString(value)
	if err != nil || base64.StdEncoding.EncodeToString(key) != value || len(key) != keySize {
		return nil, errors.New("must be canonical Base64 for exactly 32 bytes")
	}
	return key, nil
}

// validIdentifier 按协议字节上限校验标识符，并过滤空白、控制字符和歧义分隔符；当前仅用于 artifactId。
func validIdentifier(value string, maxBytes int) bool {
	if !utf8.ValidString(value) || len(value) == 0 || len(value) > maxBytes {
		return false
	}
	for _, r := range value {
		if r <= 0x20 || r == 0x7f || strings.ContainsRune("<>\"'`", r) {
			return false
		}
	}
	return true
}

// validateArgs 拒绝空值条目，但逐字节保留其他参数，使空格和 shell 元字符仍作为普通 argv 数据传递。
func validateArgs(args []string) error {
	for _, arg := range args {
		if !utf8.ValidString(arg) || strings.IndexByte(arg, 0) >= 0 {
			return errors.New("arguments must be valid UTF-8 without NUL bytes")
		}
	}
	return nil
}

// rejectDuplicateFields 在解码前遍历 JSON 对象，因为标准解码器默认允许重复键并采用后值覆盖规则。
func rejectDuplicateFields(input io.Reader) error {
	decoder := json.NewDecoder(input)
	if err := inspectJSONValue(decoder); err != nil {
		return err
	}
	if _, err := decoder.Token(); !errors.Is(err, io.EOF) {
		return errors.New("configuration contains trailing JSON data")
	}
	return nil
}

// inspectJSONValue 校验嵌套 JSON 结构并记录各对象的键，避免不同解析器对重复安全字段作出不同解释。
func inspectJSONValue(decoder *json.Decoder) error {
	token, err := decoder.Token()
	if err != nil {
		return err
	}
	delimiter, isDelimiter := token.(json.Delim)
	if !isDelimiter {
		return nil
	}
	switch delimiter {
	case '{':
		seen := make(map[string]struct{})
		for decoder.More() {
			keyToken, err := decoder.Token()
			if err != nil {
				return err
			}
			key, ok := keyToken.(string)
			if !ok {
				return errors.New("object key is not a string")
			}
			if _, exists := seen[key]; exists {
				return fmt.Errorf("duplicate object key %q", key)
			}
			seen[key] = struct{}{}
			if err := inspectJSONValue(decoder); err != nil {
				return err
			}
		}
		_, err = decoder.Token()
		return err
	case '[':
		for decoder.More() {
			if err := inspectJSONValue(decoder); err != nil {
				return err
			}
		}
		_, err = decoder.Token()
		return err
	default:
		return errors.New("unexpected JSON delimiter")
	}
}
