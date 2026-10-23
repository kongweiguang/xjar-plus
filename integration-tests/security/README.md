<!-- @author kongweiguang -->

# 独立安全探针

这些探针直接编译当前 format 与 runtime/common 源码，并使用 Go 标准库和实际子进程验证协议边界。
它们补充模块单元测试，不替代普通 JAR、Spring Boot、Solon 的真实启动矩阵，也不证明 GUI、发布包或生产环境通过验收。

## 运行条件与命令

Windows、Linux、macOS 均使用 Python 3.10+ 标准库，PATH 中需要 Java/Javac 21+、Go 1.24+。
脚本先只读检查工具与版本；不安装软件，不下载 Go 工具链，不需要 Maven。请不要同时在同一工作树运行两份探针。

```text
# 三种系统共用；没有 --java8-home 时只证明 --release 8 编译兼容。
python integration-tests/security/run.py

# Windows：指定 JDK/JRE 8 根目录，PATH 中的默认 Java/Javac 仍保持 21+。
python integration-tests/security/run.py --java8-home C:/path/to/jdk8

# Linux/macOS：传入本机 Java 8 根目录。macOS 的 JDK bundle 通常需要指向 Contents/Home。
python integration-tests/security/run.py --java8-home /path/to/jdk8

# 仅 Windows：额外在已经安装的 WSL 发行版运行 Linux 进程树用例；不要求 WSL 内安装 Go 或 Java。
python integration-tests/security/run.py --java8-home C:/path/to/jdk8 --linux-wsl --wsl-distribution Ubuntu-22.04
```

`--java8-home` 在 Windows 解析为 `bin/java.exe`，在 Linux/macOS 解析为 `bin/java`，并验证确实是 Java 8。
Linux 原生运行会由 `go test` 自动执行 Linux 进程树用例，不要加 `--linux-wsl`；macOS 不执行依赖 `/proc` 的 Linux 专属用例。
WSL 架构由 `uname -m` 识别，仅接受 x86_64/aarch64（对应 Go amd64/arm64），不将交叉编译成功当作执行通过。

每个外部命令默认限时 180 秒，可用 `--command-timeout-seconds 300` 调整（范围 1～1800）。
Go 用例还有更短的测试期限；命令超时会终止本次启动的进程树并失败，非零退出或失败不会被后续成功掩盖。
CI 应另设整个 job 的超时；Windows 宿主终止 `wsl.exe` 不等价于终止整个 WSL 发行版，因此 WSL 测试还使用内部测试期限。
Linux 进程树辅助进程另有 30 秒自退出兜底；清理断言在此之前完成，兜底不会把清理失败误判为通过。

CI 可先保存 Java 8 安装目录，再把默认 PATH 切回 Java 21，使用：

```powershell
python integration-tests/security/run.py --java8-home $env:XJP_JAVA8_HOME
```

## 覆盖与边界

- `test_run.py`：标准库验证运行器的中文/UTF-16 诊断、机器输出分离、中文空格参数、非零退出与超时拒绝；入口会自动执行。
- `FormatSecurityProbe.java`：认证数据篡改、索引边界、分块顺序/AAD、nonce 边界、尾随数据与解密失败后的持续拒绝；输出供 Go
  消费的公开测试许可证。
- `RuntimeSecurityProbe.java`：受保护路径归属、缺失索引拒绝、外部依赖不误匹配、明文/密文资源完整性。明文仅读取首字节也必须先拒绝尾部篡改、长度、CRC
  或 SHA 不符，并覆盖缓存开关。
- `interop_test.go`：独立 Go 标准库 AES-GCM/PBKDF2 验证固定向量，包括中文和 emoji 口令；验证 Java 许可证的
  appId、有效期边界和篡改拒绝。
- `broker_test.go` + `RuntimeBrokerProbe.java`：实际 Go→Java 一次性供钥，验证业务 stdin 保留、连接用后关闭以及不完整 broker
  配置禁止降级到密码。
- `process_linux_test.go`：实际 Linux 父孙进程在主动停止与父进程自然退出后的清理；读取 `/proc` 验证，而非只看 launcher
  的完成信号。Windows Job Object 的测试由 `launcher` 模块自己的测试负责。

`--java8-home` 会让 Java 两组探针及 Go→Java broker 都再用实际 Java 8 执行。
成功计数随新增回归变化，以本次输出和退出码为准；不能把未运行平台写成通过。

全部凭据、口令和许可证均为公开测试数据，不得用于部署。生成的 class、JAR、测试许可证和 Linux 测试二进制仅位于
`integration-tests/security/target/`；Go 工具可能使用其正常构建缓存。脚本不读取用户 `.issuer`、真实应用密码或业务产物，
不提交、推送、发布，也不会为重试覆盖用户输出。测试产生的临时 TCP 服务仅监听 loopback。
