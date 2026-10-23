// @author kongweiguang
package runner

import "testing"

// TestParseJavaFeatureVersion 覆盖旧版和当前厂商输出，因为兼容性取决于实际运行时版本而非可执行文件路径。
func TestParseJavaFeatureVersion(t *testing.T) {
	cases := map[string]int{
		"java version \"1.8.0_402\"":             8,
		"openjdk version \"17.0.12\" 2024-07-16": 17,
		"openjdk 21 2023-09-19":                  21,
		"openjdk version \"25-ea\" 2025-03-18":   25,
	}
	for output, expected := range cases {
		feature, err := parseJavaFeatureVersion([]byte(output))
		if err != nil || feature != expected {
			t.Errorf("parseJavaFeatureVersion(%q) = %d, %v; want %d", output, feature, err, expected)
		}
	}
}

// TestParseJavaFeatureVersionRejectsUnknownOutput 确保无法识别的厂商版本横幅不会默认被视为兼容。
func TestParseJavaFeatureVersionRejectsUnknownOutput(t *testing.T) {
	for _, output := range []string{"", "java version \"future\"", "openjdk version \"101.0\""} {
		if _, err := parseJavaFeatureVersion([]byte(output)); err == nil {
			t.Errorf("parseJavaFeatureVersion(%q) accepted unsupported output", output)
		}
	}
}
