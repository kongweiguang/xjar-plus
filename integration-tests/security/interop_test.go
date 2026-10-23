// @author kongweiguang
package securitytests

import (
	"bufio"
	"bytes"
	"crypto/aes"
	"crypto/cipher"
	"crypto/pbkdf2"
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/kongweiguang/xjar-plus/launcher/internal/license"
)

// TestStandardLibraryInteroperability 用独立 Go 标准库交叉验证，
// 不复制待审计的 Java 派生算法，以免两端共享同一错误。
func TestStandardLibraryInteroperability(t *testing.T) {
	vectors := readVectors(t)
	key := fixtureHex(t, vectors["aes.key"])
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	sealed := gcm.Seal(nil, fixtureHex(t, vectors["aes.nonce"]), fixtureHex(t, vectors["aes.plaintext"]), fixtureHex(t, vectors["aes.aad"]))
	if !bytes.Equal(sealed, fixtureHex(t, vectors["aes.sealed"])) {
		t.Fatal("AES-GCM Java/Go vector mismatch")
	}
	iterations, err := strconv.Atoi(vectors["pbkdf2.iterations"])
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"pbkdf2", "pbkdf2.unicode"} {
		password := string(fixtureHex(t, vectors[name+".password.utf8"]))
		derived, deriveErr := pbkdf2.Key(sha256.New, password, fixtureHex(t, vectors["pbkdf2.salt"]), iterations, 32)
		if deriveErr != nil {
			t.Fatal(deriveErr)
		}
		if !bytes.Equal(derived, fixtureHex(t, vectors[name+".key"])) {
			t.Fatalf("%s Java/Go vector mismatch", name)
		}
	}
}

// TestJavaIssuedLicenseBinding 让生产 Go 解码器读取刚由 Java 签发的 Unicode 许可证，
// 并确认应用身份不匹配时拒绝授权。
func TestJavaIssuedLicenseBinding(t *testing.T) {
	encoded, err := os.ReadFile(filepath.Join("target", "java-license.bin"))
	if err != nil {
		t.Fatal("run FormatSecurityProbe first: ", err)
	}
	key := fixtureHex(t, readVectors(t)["aes.key"])
	grant, err := license.Parse(encoded, key, "测试应用🔐")
	if err != nil {
		t.Fatal(err)
	}
	if valid, _, _ := grant.Evaluate(time.Unix(100, 0)); !valid {
		t.Fatal("inclusive start rejected")
	}
	if valid, _, _ := grant.Evaluate(time.Unix(200, 0)); valid {
		t.Fatal("exclusive end accepted")
	}
	if _, err := license.Parse(encoded, key, "另一应用🔐"); err == nil {
		t.Fatal("foreign appId accepted")
	}
	encoded[len(encoded)-1] ^= 1
	if _, err := license.Parse(encoded, key, "测试应用🔐"); err == nil {
		t.Fatal("modified tag accepted")
	}
}

// readVectors 只读取仓库内公开向量并限制单行长度，
// 避免安全探针误用业务凭据或本机状态。
func readVectors(t *testing.T) map[string]string {
	t.Helper()
	path := filepath.Join("..", "..", "xjar-plus-format", "src", "test", "resources", "vectors-v2.txt")
	file, err := os.Open(path)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	values := make(map[string]string)
	scanner := bufio.NewScanner(file)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		name, value, found := strings.Cut(line, "=")
		if !found {
			t.Fatal("malformed public vector")
		}
		values[name] = value
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	return values
}

// fixtureHex 拒绝非法向量，避免空密钥或截断数据让测试偏离实际协议。
func fixtureHex(t *testing.T, value string) []byte {
	t.Helper()
	decoded, err := hex.DecodeString(value)
	if err != nil || value == "" {
		t.Fatal("invalid public fixture hex")
	}
	return decoded
}
