// @author kongweiguang
package config

import (
	"encoding/base64"
	"strings"
	"testing"
)

// TestParseAcceptsTheV2WireShape 固定 Java 与 Go 间的字段契约（含归档最低 Java 版本），并区分空 argv 数组与缺失或 null。
func TestParseAcceptsTheV2WireShape(t *testing.T) {
	key := base64.StdEncoding.EncodeToString(make([]byte, 32))
	input := `{"version":2,"appId":"示例 app","artifactId":"12345678-1234-1234-1234-123456789abc","dataKey":"` + key + `","licenseKey":"` + key + `","embeddedJdk":false,"minimumJava":21,"jvmArgs":[],"appArgs":[]}`
	cfg, err := Parse([]byte(input))
	if err != nil {
		t.Fatalf("Parse() error = %v", err)
	}
	if cfg.AppID != "示例 app" || len(cfg.DataKey) != 32 || cfg.EmbeddedJDK || cfg.MinimumJava != 21 || cfg.JVMArgs == nil || cfg.AppArgs == nil {
		t.Fatalf("Parse() returned unexpected config: %+v", cfg)
	}
}

// TestParseRejectsUnknownAndTrailingFields 防止 Java 与 Go 打包端对影响运行的配置字段产生静默分歧。
func TestParseRejectsUnknownAndTrailingFields(t *testing.T) {
	key := base64.StdEncoding.EncodeToString(make([]byte, 32))
	valid := `{"version":2,"appId":"demo","artifactId":"artifact","dataKey":"` + key + `","licenseKey":"` + key + `","embeddedJdk":false,"minimumJava":21,"jvmArgs":[],"appArgs":[]}`
	for name, input := range map[string]string{
		"unknown":            strings.TrimSuffix(valid, "}") + `,"secret":true}`,
		"trailing":           valid + `{}`,
		"nullArgs":           strings.Replace(valid, `"appArgs":[]`, `"appArgs":null`, 1),
		"duplicate":          strings.Replace(valid, `"version":2`, `"version":2,"version":2`, 1),
		"missingMinimumJava": strings.Replace(valid, `,"minimumJava":21`, "", 1),
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := Parse([]byte(input)); err == nil {
				t.Fatal("Parse() accepted malformed configuration")
			}
		})
	}
}

// TestValidateBoundsBrokerIdentifiers 实际验证 artifactId 的 180 字节上限；AppID 仍按 UTF-8 字节精确契约校验。
func TestValidateBoundsBrokerIdentifiers(t *testing.T) {
	cfg := Config{Version: 2, AppID: "demo", ArtifactID: "artifact", DataKey: make([]byte, 32), LicenseKey: make([]byte, 32), MinimumJava: 8, JVMArgs: []string{}, AppArgs: []string{}}
	cfg.ArtifactID = strings.Repeat("a", 181)
	if err := cfg.Validate(); err == nil {
		t.Fatal("Validate() accepted an overlong artifactId")
	}
}

// TestValidateBoundsMinimumJava 防止异常字节码元数据绕过运行时兼容性所需的最低值和上限。
func TestValidateBoundsMinimumJava(t *testing.T) {
	cfg := Config{Version: 2, AppID: "demo", ArtifactID: "artifact", DataKey: make([]byte, 32), LicenseKey: make([]byte, 32), MinimumJava: 8, JVMArgs: []string{}, AppArgs: []string{}}
	for _, minimumJava := range []int{0, 7, 101} {
		cfg.MinimumJava = minimumJava
		if err := cfg.Validate(); err == nil {
			t.Fatalf("Validate() accepted minimumJava %d", minimumJava)
		}
	}
	cfg.MinimumJava = 100
	if err := cfg.Validate(); err != nil {
		t.Fatalf("Validate() rejected upper supported minimumJava: %v", err)
	}
}
