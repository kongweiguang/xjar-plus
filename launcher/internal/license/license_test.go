// @author kongweiguang
package license

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/binary"
	"encoding/hex"
	"testing"
	"time"
)

// TestParseReadsTheSharedJavaVector 使用仓库共享的 XJL2 固定向量，确保 Go 验收始终符合 Java 的认证线格式。
func TestParseReadsTheSharedJavaVector(t *testing.T) {
	key, _ := hex.DecodeString("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	record, _ := hex.DecodeString("584a4c32f0e0d0c0b0a0908070605040ae38bc691fd6c76089476f336ec2aa472b0cdcbaa39b32cba91fccdb093c8bf8a93690ade2622b9e7fdf920d429098")
	grant, err := Parse(record, key, "vector-demo")
	if err != nil {
		t.Fatalf("Parse() error = %v", err)
	}
	if grant.AppID != "vector-demo" || grant.NotBeforeUnix != 1735689600 || !grant.Perpetual {
		t.Fatalf("Parse() returned unexpected grant: %+v", grant)
	}
}

// TestParsePreservesUnicodeSpaceAppIDBytes 使用 Java 共享向量，防止应用绑定日后引入依赖区域设置的裁剪或归一化。
func TestParsePreservesUnicodeSpaceAppIDBytes(t *testing.T) {
	key, _ := hex.DecodeString("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	record, _ := hex.DecodeString("584a4c32f0e0d0c0b0a0908070605040ae38bc688e171ef058be62367bdfc5472b0cbba9529eb2cba91fccdb093c87e694bb8b6bb8044024c1d39ab9f5f9")
	grant, err := Parse(record, key, "示例 app")
	if err != nil {
		t.Fatalf("Parse() rejected the exact Unicode-space appId: %v", err)
	}
	if grant.AppID != "示例 app" || grant.NotBeforeUnix != 1735689600 || !grant.Perpetual {
		t.Fatalf("Parse() returned unexpected grant: %+v", grant)
	}
}

// TestParseRejectsTamperForeignAppAndTrailingBytes 固定认证、应用绑定和记录长度的 fail-closed 行为。
func TestParseRejectsTamperForeignAppAndTrailingBytes(t *testing.T) {
	key, _ := hex.DecodeString("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
	record, _ := hex.DecodeString("584a4c32f0e0d0c0b0a0908070605040ae38bc691fd6c76089476f336ec2aa472b0cdcbaa39b32cba91fccdb093c8bf8a93690ade2622b9e7fdf920d429098")
	if _, err := Parse(record, key, "other-app"); err == nil {
		t.Fatal("Parse() accepted a foreign appId")
	}
	tampered := append([]byte(nil), record...)
	tampered[len(tampered)-1] ^= 1
	if _, err := Parse(tampered, key, "vector-demo"); err == nil {
		t.Fatal("Parse() accepted a modified tag")
	}
	if _, err := Parse(append(record, 0), key, "vector-demo"); err == nil {
		t.Fatal("Parse() accepted trailing bytes")
	}
}

// TestEvaluateUsesInclusiveStartExclusiveEnd 固定 UTC 有效区间为起点包含、终点不包含，确保 Java 与 Go 在边界判定一致。
func TestEvaluateUsesInclusiveStartExclusiveEnd(t *testing.T) {
	grant := Grant{AppID: "demo", NotBeforeUnix: 100, NotAfterUnix: 200}
	if valid, _, _ := grant.Evaluate(time.Unix(99, 0)); valid {
		t.Fatal("grant was valid before notBefore")
	}
	if valid, _, _ := grant.Evaluate(time.Unix(100, 0)); !valid {
		t.Fatal("grant was invalid at inclusive notBefore")
	}
	if valid, _, _ := grant.Evaluate(time.Unix(200, 0)); valid {
		t.Fatal("grant was valid at exclusive notAfter")
	}
}

// TestParseRejectsInvalidWindow 将无效明文封装进有效 GCM 信封，证明有效期校验独立于认证步骤。
func TestParseRejectsInvalidWindow(t *testing.T) {
	key := make([]byte, keySize)
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatal(err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatal(err)
	}
	plain := make([]byte, 4+4+16)
	binary.BigEndian.PutUint32(plain[:4], 4)
	copy(plain[4:8], "demo")
	binary.BigEndian.PutUint64(plain[8:16], 200)
	binary.BigEndian.PutUint64(plain[16:24], 100)
	nonce := make([]byte, nonceSize)
	sealed := gcm.Seal(nil, nonce, plain, licenseAAD)
	record := append([]byte(magic), nonce...)
	record = append(record, sealed...)
	if _, err := Parse(record, key, "demo"); err == nil {
		t.Fatal("Parse() accepted an inverted validity window")
	}
}
