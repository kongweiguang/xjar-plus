// xjar-plus 应用启动器。
//
// 本文件由 XGo.make() 作为模板整体生成，打包时 Java 侧将占位符（#{}）替换为实际值，
// 因此必须保持：单文件、仅使用 Go 标准库、占位符格式不变。
//
// 设计目标：
//  1. 目标机器只需一个可执行文件，不需要 Go 环境；
//  2. 启动过程输出人性化的步骤提示与失败原因，便于客户与运维排查；
//  3. 授权状态 HTTP 服务仅供查看有效期与可用状态，不泄露任何敏感信息；
//  4. 加密密钥只通过标准输入传递给应用进程，不进入命令行参数（防 /proc 泄露）。
//
// 环境变量：
//
//	XJAR_LICENSE_HTTP_ADDR  授权状态服务监听地址（默认从 127.0.0.1:19527 起尝试，勿对外暴露）
//
// 文件内按职责分区，从上到下：
//
//	常量与内置配置 → 终端输出与用户提示 → 入口与启动编排 → license 读取与校验
//	→ 授权状态 HTTP 服务 → 运行环境部署 → 应用进程启动 → 密码学工具
//	→ 通用工具 → 错误与退出 → 数据类型
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
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// ---------------------------------------------------------------------------
// 常量与内置配置
// ---------------------------------------------------------------------------

const (
	// 内嵌资源：打包进可执行文件的 JDK 压缩包与应用 jar。
	jdkZipPath = "resource/jdk.zip"
	jarPath    = "resource/#{appName}"

	// licenseFileName 授权文件，位于可执行文件同目录。
	licenseFileName = "key.x"

	// dateFormat 授权有效期时间格式，与 Java 侧保持一致。
	dateFormat = "2006-01-02 15:04:05"

	// 以下占位符在打包时由 Java 侧替换为实际值。
	// validStartDate 内置有效期开始时间
	validStartDate = "#{validStartDate}"
	// validEndDate 内置有效期结束时间（空表示永久有效）
	validEndDate = "#{validEndDate}"
	// hexKey 授权文件解密密钥（hex 字符串）
	hexKey = "#{hexKey}"
	// hexIV 授权文件解密向量（hex 字符串）
	hexIV = "#{hexIV}"
	// code 应用编码，用于校验授权文件归属
	code = "#{code}"
	// licenseHTTPEnv 授权状态服务监听地址的环境变量名。
	// 不设置时使用默认回环地址，不会对外暴露。
	licenseHTTPEnv = "XJAR_LICENSE_HTTP_ADDR"
	// licenseHTTPPrefix 授权状态服务所有接口的统一路径前缀。
	licenseHTTPPrefix = "/xjp"
	// defaultLicenseHTTPPort 默认监听端口。未显式配置监听地址时，绑定失败会继续尝试后续端口。
	defaultLicenseHTTPPort = 19527
	// licenseHTTPPortFallbacks 默认端口不可用时，最多继续尝试的后续端口数量。
	licenseHTTPPortFallbacks = 30

	// launcherVersion 启动器版本，显示在启动横幅中。
	launcherVersion = "1.0.0"

	// boxWidth 横幅与失败提示框的宽度（字符数）。
	boxWidth = 60

	// 授权状态服务的超时与报文限制，防止慢连接与超大请求拖垮服务。
	httpReadHeaderTimeout = 5 * time.Second
	httpReadTimeout       = 10 * time.Second
	httpWriteTimeout      = 10 * time.Second
	httpIdleTimeout       = 60 * time.Second
	httpMaxHeaderBytes    = 1 << 20
)

//go:embed resource/*
var resource embed.FS

// xKey 内置加密密钥，启动时通过标准输入传给应用进程，不进入命令行。
var xKey = XKey{
	algorithm: []byte{#{xKey.algorithm}},
	keysize:   []byte{#{xKey.keysize}},
	ivsize:    []byte{#{xKey.ivsize}},
	password:  []byte{#{xKey.password}},
}

// ---------------------------------------------------------------------------
// 终端输出与用户提示
// ---------------------------------------------------------------------------

// ANSI 颜色码：仅当输出目标是终端时使用，避免污染重定向的日志文件。
const (
	ansiReset  = "\033[0m"
	ansiBold   = "\033[1m"
	ansiGreen  = "\033[32m"
	ansiYellow = "\033[33m"
	ansiRed    = "\033[31m"
)

// 步骤结果标记，统一提示样式。
const (
	markOK   = "[OK]"
	markWarn = "[警告]"
	markFail = "[失败]"
)

// stdoutIsTerminal 判断标准输出是否为终端（字符设备）。
// 只有终端输出才使用颜色，重定向到文件或管道时输出纯文本。
func stdoutIsTerminal() bool {
	info, err := os.Stdout.Stat()
	if err != nil {
		return false
	}
	return info.Mode()&os.ModeCharDevice != 0
}

// useColor 是否启用终端颜色：输出为终端且未设置 NO_COLOR 环境变量时启用（遵循 NO_COLOR 约定）。
var useColor = stdoutIsTerminal() && os.Getenv("NO_COLOR") == ""

// paint 为文本附加 ANSI 颜色；未启用颜色时原样返回。
func paint(code, text string) string {
	if !useColor {
		return text
	}
	return code + text + ansiReset
}

// printBanner 输出启动横幅：产品名、版本、应用编码与授权有效期。
// 授权文件不可用时展示打包时写入的内置有效期。
func printBanner(lic *License) {
	codeStr := code
	startStr, endStr := validStartDate, validEndDate
	if lic != nil {
		codeStr = lic.Code
		startStr, endStr = lic.ValidStartDate, lic.ValidEndDate
	}

	line := strings.Repeat("=", boxWidth)
	fmt.Println(line)
	fmt.Printf("  %s v%s\n", paint(ansiBold, "xjar-plus 应用启动器"), launcherVersion)
	fmt.Printf("  应用编码  : %s\n", codeStr)
	if endStr == "" {
		fmt.Printf("  授权有效期: %s ~ 永久有效\n", startStr)
	} else {
		fmt.Printf("  授权有效期: %s ~ %s\n", startStr, endStr)
	}
	fmt.Println(line)
	fmt.Println()
}

// StepRunner 按顺序输出启动步骤的进度与结果，格式形如 "[2/5] 校验授权有效期 ... [OK]"。
type StepRunner struct {
	total  int // 总步骤数
	number int // 当前步骤序号
}

// newStepRunner 创建步骤进度器。
func newStepRunner(total int) *StepRunner {
	return &StepRunner{total: total}
}

// begin 输出步骤标题，随后调用 ok/warn/fail 补充结果。
func (s *StepRunner) begin(name string) {
	s.number++
	fmt.Printf("[%d/%d] %s ... ", s.number, s.total, name)
}

// ok 标记步骤成功，可附带补充信息（如剩余有效期、访问地址）。
func (s *StepRunner) ok(extra ...string) {
	fmt.Println(paint(ansiGreen, markOK))
	for _, line := range extra {
		fmt.Println("      " + line)
	}
}

// warn 标记步骤未完全成功并输出警告原因（不阻断启动）。
func (s *StepRunner) warn(message string) {
	fmt.Println(paint(ansiYellow, markWarn))
	fmt.Println("      " + message)
}

// fail 标记步骤失败，详细原因由 exitWithErr 的失败提示框展示。
func (s *StepRunner) fail() {
	fmt.Println(paint(ansiRed, markFail))
}

// ---------------------------------------------------------------------------
// 入口与启动编排
// ---------------------------------------------------------------------------

// main 启动器入口：运行启动流程，失败时输出人性化提示后以退出码 1 退出。
func main() {
	if err := run(); err != nil {
		exitWithErr(err) // 打印失败提示框后退出，永不返回
	}
	// 走到这里说明应用进程正常退出（退出码 0）
	fmt.Println(paint(ansiGreen, "应用已正常退出"))
}

// run 串联整个启动流程，共 5 个步骤：
//  1. 校验授权文件  2. 校验授权有效期  3. 启动授权状态服务
//  4. 准备运行环境  5. 启动应用（阻塞至应用退出）
//
// 任何步骤失败都会以分类错误码终止启动（fail fast），并给出修复建议。
func run() error {
	// 先读取授权文件：读取失败不阻断启动，回退到内置有效期并给出警告
	license, licenseErr := readLicense()

	// 启动横幅：展示应用编码与授权有效期
	printBanner(license)

	steps := newStepRunner(5)

	// 步骤 1/5：校验授权文件
	steps.begin("校验授权文件")
	if licenseErr != nil {
		steps.warn(fmt.Sprintf("授权文件不可用，将使用打包时的内置有效期: %s", sanitizeError(licenseErr)))
	} else {
		steps.ok()
	}

	// 步骤 2/5：校验授权有效期
	steps.begin("校验授权有效期")
	duration, extArgs, validityErr := checkValidity(license)
	if validityErr != nil {
		steps.fail()
		return newExitError("E_VALIDITY", "授权校验失败", validityErr)
	}
	perpetual := validEndDate == ""
	if license != nil {
		perpetual = license.ValidEndDate == ""
	}
	if perpetual {
		steps.ok("永久有效")
	} else {
		steps.ok(fmt.Sprintf("剩余有效期: %s", formatDuration(duration)))
	}

	// 步骤 3/5：启动授权状态服务（与应用同生命周期，绑定失败立即终止启动）
	steps.begin("启动授权状态服务")
	statusAddr, err := startStatusServer(license, licenseErr)
	if err != nil {
		steps.fail()
		return newExitError("E_STATUS", "授权状态服务启动失败", err)
	}
	steps.ok(fmt.Sprintf("访问地址: http://%s%s/license", statusAddr, licenseHTTPPrefix))

	// 步骤 4/5：准备运行环境（解压 JDK、写入应用 jar、设置执行权限）
	steps.begin("准备运行环境")
	if err := deployRuntime(runtimeJdkPath()); err != nil {
		steps.fail()
		return newExitError("E_RUNTIME", "运行环境准备失败", err)
	}
	steps.ok()

	// 步骤 5/5：启动应用（阻塞至应用退出）
	steps.begin("启动应用")
	err = launchApp(runtimeJdkPath(), extArgs, duration, func(pid int) {
		// 进程创建成功后立即提示，让用户先看到"已启动"再看到应用自身日志
		steps.ok(fmt.Sprintf("进程已创建 (PID: %d)", pid))
	})
	if err != nil {
		steps.fail()
		return newExitError("E_APP", "应用运行异常", err)
	}

	return nil
}

// ---------------------------------------------------------------------------
// license 读取与校验
// ---------------------------------------------------------------------------

// License 授权文件 (key.x) 解密后的明文结构。
type License struct {
	Code           string `json:"code"`           // 应用编码，须与启动器内置 code 一致
	ValidStartDate string `json:"validStartDate"` // 有效期开始时间（yyyy-MM-dd HH:mm:ss）
	ValidEndDate   string `json:"validEndDate"`   // 有效期结束时间（空表示永久有效）
	Args           string `json:"args"`           // 覆盖启动器内置的 Java 启动参数
}

// verify 校验授权文件是否属于当前应用：code 必须与打包时写入的一致。
func (l *License) verify() error {
	if l.Code != code {
		return fmt.Errorf("license is not valid for this application")
	}
	return nil
}

// readLicense 读取并解密可执行文件同目录下的 key.x。
// 返回的 error 不阻断启动流程（调用方决定回退到内置有效期），因此错误信息可包含路径等细节。
func readLicense() (*License, error) {
	exePath, err := os.Executable()
	if err != nil {
		return nil, fmt.Errorf("locate executable: %w", err)
	}
	licensePath := filepath.Join(filepath.Dir(exePath), licenseFileName)
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

	var lic License
	if err := json.Unmarshal(plain, &lic); err != nil {
		return nil, fmt.Errorf("parse license file: %w", err)
	}
	if err := lic.verify(); err != nil {
		return nil, err
	}

	return &lic, nil
}

// checkValidity 校验授权有效期，返回剩余时长与应用启动参数。
// 永久有效（ValidEndDate 为空）时 duration 为 -1；校验失败返回具体原因。
func checkValidity(lic *License) (time.Duration, string, error) {
	startStr, endStr, args := validStartDate, validEndDate, ""
	if lic != nil {
		startStr, endStr, args = lic.ValidStartDate, lic.ValidEndDate, lic.Args
	}

	start, err := time.ParseInLocation(dateFormat, startStr, time.Local)
	if err != nil {
		return -1, args, fmt.Errorf("invalid start date")
	}

	now := time.Now()
	if now.Before(start) {
		return -1, args, fmt.Errorf("application is not valid yet")
	}

	if endStr == "" {
		return -1, args, nil
	}

	end, err := time.ParseInLocation(dateFormat, endStr, time.Local)
	if err != nil {
		return -1, args, fmt.Errorf("invalid end date")
	}
	if now.After(end) {
		return -1, args, fmt.Errorf("application has expired")
	}

	return end.Sub(now), args, nil
}

// ---------------------------------------------------------------------------
// 授权状态 HTTP 服务
//
// 该服务无鉴权，仅供客户查看授权信息，因此只暴露有效期与可用状态，
// 绝不含密码、密钥、应用编码、启动参数、文件路径等敏感信息。
// ---------------------------------------------------------------------------

// LicenseStatus 对外暴露的授权状态快照。
type LicenseStatus struct {
	Available      bool   `json:"available"`           // license 文件是否可用（存在、可解密、编码匹配）
	Valid          bool   `json:"valid"`               // 当前是否在有效期内
	ValidStartDate string `json:"validStartDate"`      // 有效期开始时间
	ValidEndDate   string `json:"validEndDate"`        // 有效期结束时间（空表示永久有效）
	Perpetual      bool   `json:"perpetual"`           // 是否永久有效
	RemainingMs    int64  `json:"remainingMs"`         // 距到期剩余毫秒（永久有效或已失效为 0）
	Remaining      string `json:"remaining,omitempty"` // 剩余时长的可读表示
	ServerTime     string `json:"serverTime"`          // 当前服务器时间
	Message        string `json:"message,omitempty"`   // 不可用/失效原因说明
}

// buildLicenseStatus 构造授权状态快照，每次调用都用当前时间刷新。
// 有效期在请求时刻动态判定，因此同时覆盖"启动时已失效"与"运行期间到期"两种场景。
func buildLicenseStatus(lic *License, licenseErr error) LicenseStatus {
	now := time.Now()
	status := LicenseStatus{
		ValidStartDate: validStartDate,
		ValidEndDate:   validEndDate,
		ServerTime:     now.Format(dateFormat),
		Available:      licenseErr == nil && lic != nil,
	}
	if lic != nil {
		status.ValidStartDate = lic.ValidStartDate
		status.ValidEndDate = lic.ValidEndDate
	}

	status.Perpetual = status.ValidEndDate == ""
	status.Valid = true

	if !status.Perpetual {
		// 分别校验开始与结束时间，兜底覆盖系统时间被回拨等异常场景
		if start, err := time.ParseInLocation(dateFormat, status.ValidStartDate, time.Local); err == nil && now.Before(start) {
			status.Valid = false
			status.Message = "application is not valid yet"
			return status
		}
		if end, err := time.ParseInLocation(dateFormat, status.ValidEndDate, time.Local); err == nil {
			remaining := end.Sub(now)
			if remaining <= 0 {
				status.Valid = false
				status.Message = "application has expired"
			} else {
				status.RemainingMs = remaining.Milliseconds()
				status.Remaining = formatDuration(remaining)
			}
		}
	}

	if !status.Valid && status.Message == "" {
		status.Message = licenseMessage(licenseErr)
	}

	return status
}

// licenseMessage 生成不可用原因文案。
// 不向外部暴露"内置兜底有效期"等内部机制，因此与 readLicense 的详细错误分开处理。
func licenseMessage(licenseErr error) string {
	if licenseErr == nil {
		return ""
	}
	return "license file is unavailable"
}

// startStatusServer 启动授权状态服务，返回实际监听地址。
// 先同步绑定端口：显式配置的地址绑定失败会立即报错；使用默认地址时会依次尝试端口范围。
// 服务本体在后台 goroutine 运行，与启动器同生命周期。
func startStatusServer(lic *License, licenseErr error) (string, error) {
	addr := strings.TrimSpace(os.Getenv(licenseHTTPEnv))
	var ln net.Listener
	if addr != "" {
		// 显式配置监听地址时只尝试该地址，避免悄悄偏离用户配置。
		var err error
		ln, err = net.Listen("tcp", addr)
		if err != nil {
			return "", fmt.Errorf("listen on %s: %w", addr, err)
		}
	} else {
		// 未配置时从默认端口开始，依次尝试后续 30 个端口。
		var lastErr error
		for port := defaultLicenseHTTPPort; port <= defaultLicenseHTTPPort+licenseHTTPPortFallbacks; port++ {
			candidate := fmt.Sprintf("127.0.0.1:%d", port)
			ln, lastErr = net.Listen("tcp", candidate)
			if lastErr == nil {
				break
			}
		}
		if ln == nil {
			return "", fmt.Errorf(
				"listen on ports %d-%d: %w",
				defaultLicenseHTTPPort,
				defaultLicenseHTTPPort+licenseHTTPPortFallbacks,
				lastErr,
			)
		}
	}

	mux := http.NewServeMux()
	statusHandler := statusHandlerFunc(lic, licenseErr)
	mux.HandleFunc(licenseHTTPPrefix+"/license", statusHandler)
	mux.HandleFunc(licenseHTTPPrefix+"/license/status", statusHandler)
	mux.HandleFunc(licenseHTTPPrefix+"/healthz", healthzHandler)

	server := &http.Server{
		Handler:           recoverMiddleware(mux),
		ReadHeaderTimeout: httpReadHeaderTimeout,
		ReadTimeout:       httpReadTimeout,
		WriteTimeout:      httpWriteTimeout,
		IdleTimeout:       httpIdleTimeout,
		MaxHeaderBytes:    httpMaxHeaderBytes,
	}

	// 后台运行：Serve 返回非 ErrServerClosed 时记录诊断日志
	go func() {
		if err := server.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("[warn] license status server stopped unexpectedly: %s", err)
		}
	}()

	return ln.Addr().String(), nil
}

// statusHandlerFunc 返回授权状态查询处理器：仅允许 GET/HEAD，返回 JSON。
func statusHandlerFunc(lic *License, licenseErr error) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !methodAllowed(w, r) {
			return
		}

		// 每次请求重新构造状态快照，实时反映剩余时长与到期状态
		status := buildLicenseStatus(lic, licenseErr)

		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		// 授权状态是动态数据，禁止代理与浏览器缓存
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		if !status.Valid {
			w.WriteHeader(http.StatusServiceUnavailable)
		}
		enc := json.NewEncoder(w)
		enc.SetIndent("", "  ")
		if err := enc.Encode(status); err != nil {
			log.Printf("[warn] write license status response: %s", err)
		}
	}
}

// healthzHandler 存活探针：仅返回固定文本，供监控系统使用。
func healthzHandler(w http.ResponseWriter, r *http.Request) {
	if !methodAllowed(w, r) {
		return
	}
	w.WriteHeader(http.StatusOK)
	if _, err := io.WriteString(w, "ok"); err != nil {
		log.Printf("[warn] write healthz response: %s", err)
	}
}

// methodAllowed 仅放行 GET/HEAD，其余方法统一返回 405（服务只读）。
func methodAllowed(w http.ResponseWriter, r *http.Request) bool {
	if r.Method == http.MethodGet || r.Method == http.MethodHead {
		return true
	}
	w.WriteHeader(http.StatusMethodNotAllowed)
	return false
}

// recoverMiddleware 兜底拦截处理器 panic，防止单个请求崩溃整个启动器进程。
func recoverMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				log.Printf("[warn] panic while serving %s: %v", r.URL.Path, rec)
				http.Error(w, "internal error", http.StatusInternalServerError)
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// ---------------------------------------------------------------------------
// 运行环境部署
// ---------------------------------------------------------------------------

// runtimeJdkPath 返回 JDK 解压目录。目录基于应用编码命名，避免多应用互相覆盖。
func runtimeJdkPath() string {
	return filepath.Join(os.TempDir(), "deploy", tempDirName(code), "jdk")
}

// tempDirName 将任意字符串转换为目录名：取 hex 编码前 10 位，空值给默认名。
func tempDirName(value string) string {
	name := hex.EncodeToString([]byte(value))
	if name == "" {
		name = hex.EncodeToString([]byte("default"))
	}
	if len(name) > 10 {
		return name[:10]
	}
	return name
}

// deployRuntime 清理旧目录、解压内置 JDK、写入应用 jar 并设置执行权限。
func deployRuntime(jdkPath string) error {
	if err := os.RemoveAll(jdkPath); err != nil {
		return fmt.Errorf("clean runtime directory: %w", err)
	}

	jdkZip, err := resource.ReadFile(jdkZipPath)
	if err != nil {
		return fmt.Errorf("read embedded runtime: %w", err)
	}
	if err := unzipFromBytes(jdkZip, jdkPath); err != nil {
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

// chmodJdkCommands 为 JDK bin 目录下的所有命令设置可执行权限（Windows 下幂等）。
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

// unzipFromBytes 将内存中的 zip 数据安全解压到 dest。
// 通过路径规范化校验防止 zip-slip（解压路径逃逸出目标目录）。
func unzipFromBytes(data []byte, dest string) error {
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
		// 拒绝绝对路径（zip-slip 第一道防线）
		if filepath.IsAbs(f.Name) {
			return fmt.Errorf("invalid file path: %s", f.Name)
		}

		target := filepath.Join(cleanDest, f.Name)
		cleanTarget, err := filepath.Abs(target)
		if err != nil {
			return fmt.Errorf("resolve runtime file: %w", err)
		}
		// 规范化后必须仍位于目标目录内（zip-slip 第二道防线）
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
			_ = src.Close()
			return fmt.Errorf("create runtime file: %w", err)
		}

		if _, err := io.Copy(dst, src); err != nil {
			_ = src.Close()
			_ = dst.Close()
			return fmt.Errorf("copy runtime file: %w", err)
		}

		if err := src.Close(); err != nil {
			_ = dst.Close()
			return fmt.Errorf("close runtime archive entry: %w", err)
		}
		if err := dst.Close(); err != nil {
			return fmt.Errorf("close runtime file: %w", err)
		}
	}

	return nil
}

// ---------------------------------------------------------------------------
// 应用进程启动
// ---------------------------------------------------------------------------

// launchApp 启动并等待应用进程结束。
// started 回调在进程创建成功后调用（用于输出"已启动"提示）；
// 返回 nil 表示应用进程正常退出（退出码 0）。
func launchApp(jdkPath, extArgs string, duration time.Duration, started func(pid int)) error {
	args, err := parseArgs(strings.Join([]string{"#{jarArgs}", extArgs}, " "))
	if err != nil {
		return fmt.Errorf("parse java args: %w", err)
	}
	args = append(args, "-jar", "app.jar")

	// 设置了期限时，超时后自动终止应用进程
	ctx := context.Background()
	var cancel context.CancelFunc
	if duration > 0 {
		ctx, cancel = context.WithTimeout(ctx, duration)
		defer cancel()
	}

	javaPath := filepath.Join(jdkPath, "bin", "java")
	cmd := exec.CommandContext(ctx, javaPath, args...)
	cmd.Dir = filepath.Join(jdkPath, "bin")
	// 密钥通过标准输入注入，不进入命令行参数
	cmd.Stdin = bytes.NewReader(encodeKey())
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Start(); err != nil {
		return fmt.Errorf("start application process: %w", err)
	}
	if started != nil {
		started(cmd.Process.Pid)
	}

	err = cmd.Wait()
	if ctx.Err() == context.DeadlineExceeded {
		return fmt.Errorf("process timed out after %s", duration)
	}
	if err == nil {
		return nil
	}
	if exitErr, ok := err.(*exec.ExitError); ok {
		return fmt.Errorf("application process exited with code %d", exitErr.ExitCode())
	}
	return fmt.Errorf("wait application process: %w", err)
}

// encodeKey 将密钥信息编码为应用进程通过标准输入读取的格式（每行一个字段）。
func encodeKey() []byte {
	return bytes.Join([][]byte{
		xKey.algorithm, {13, 10},
		xKey.keysize, {13, 10},
		xKey.ivsize, {13, 10},
		xKey.password, {13, 10},
	}, []byte{})
}

// parseArgs 解析命令行参数字符串，支持单双引号包裹与反斜杠转义。
// 状态机：按空白符分词，引号内的空格不切分，反斜杠仅转义引号与自身。
func parseArgs(input string) ([]string, error) {
	var args []string
	var current strings.Builder
	var quote rune
	pendingBackslash := false
	tokenStarted := false

	for _, r := range input {
		switch {
		case pendingBackslash:
			// 仅当后续字符是引号或反斜杠时才消费转义，否则保留反斜杠原样输出
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

// ---------------------------------------------------------------------------
// 密码学工具
// ---------------------------------------------------------------------------

// decryptAesCbc 使用 AES-CBC 解密并去除 PKCS5 填充。
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

	return unpadPKCS5(plain)
}

// unpadPKCS5 校验并去除 PKCS5/PKCS7 填充。
func unpadPKCS5(data []byte) ([]byte, error) {
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

// ---------------------------------------------------------------------------
// 通用工具
// ---------------------------------------------------------------------------

// formatDuration 将时长格式化为易读的 "Xd Yh Zm Ws"。
func formatDuration(d time.Duration) string {
	if d < 0 {
		d = 0
	}
	days := d / (24 * time.Hour)
	d -= days * 24 * time.Hour
	hours := d / time.Hour
	d -= hours * time.Hour
	minutes := d / time.Minute
	d -= minutes * time.Minute
	seconds := d / time.Second
	return fmt.Sprintf("%dd %dh %dm %ds", days, hours, minutes, seconds)
}

// ---------------------------------------------------------------------------
// 错误与退出
// ---------------------------------------------------------------------------

// exitError 带分类错误码的启动失败错误。
// message 面向用户（不含路径等敏感细节），cause 为内部原因（展示前经 sanitizeError 脱敏）。
type exitError struct {
	code    string // 错误码（E_STATUS/E_VALIDITY/E_RUNTIME/E_APP）
	message string // 面向用户的阶段说明
	cause   error  // 内部原因
}

// Error 实现 error 接口。
func (e *exitError) Error() string {
	return e.message
}

// newExitError 构造带分类错误码的退出错误。
func newExitError(code, message string, cause error) error {
	return &exitError{code: code, message: message, cause: cause}
}

// exitWithErr 输出人性化的失败提示框（阶段、原因、操作建议、错误码），随后以退出码 1 退出。
func exitWithErr(err error) {
	var ee *exitError
	if !errors.As(err, &ee) {
		// 非预期的内部错误：不展示堆栈，仅给出脱敏原因
		fmt.Println(paint(ansiRed+ansiBold, "\n[启动失败]"))
		fmt.Printf("原因: %s\n", sanitizeError(err))
		os.Exit(1)
	}

	fmt.Println()
	fmt.Println(strings.Repeat("-", boxWidth))
	fmt.Println(paint(ansiRed+ansiBold, "[启动失败]"))
	fmt.Printf("阶段: %s\n", ee.message)
	if ee.cause != nil {
		fmt.Printf("原因: %s\n", sanitizeError(ee.cause))
	}
	fmt.Printf("提示: %s\n", exitHint(ee.code))
	fmt.Printf("错误码: %s\n", ee.code)
	fmt.Println(strings.Repeat("-", boxWidth))
	os.Exit(1)
}

// exitHint 按错误码返回给用户的操作建议。
func exitHint(code string) string {
	switch code {
	case "E_STATUS":
		return "请检查端口是否被占用，或通过环境变量 XJAR_LICENSE_HTTP_ADDR 更换监听地址后重试"
	case "E_VALIDITY":
		return "授权无效或已过期，请联系供应商更新授权文件 (key.x)"
	case "E_RUNTIME":
		return "请检查磁盘空间与文件权限后重试，或联系技术支持"
	case "E_APP":
		return "请检查应用日志定位具体原因，或联系技术支持"
	default:
		return "请根据上方错误信息排查，或联系技术支持"
	}
}

// sanitizeError 将错误转换为对外安全的信息：含路径或分隔符的错误统一替换为通用提示，
// 避免泄露宿主机目录结构。
func sanitizeError(err error) string {
	if err == nil {
		return ""
	}
	msg := err.Error()
	if strings.Contains(msg, string(os.PathSeparator)) || strings.Contains(msg, ":\\") || strings.Contains(msg, "/") {
		return "please check the local runtime files and permissions"
	}
	return msg
}

// ---------------------------------------------------------------------------
// 数据类型
// ---------------------------------------------------------------------------

// XKey 内置加密密钥信息，启动时通过标准输入传给应用进程。
type XKey struct {
	algorithm []byte // 加密算法名（如 AES/CBC/PKCS5Padding）
	keysize   []byte // 密钥长度
	ivsize    []byte // IV 长度
	password  []byte // 加密口令
}
