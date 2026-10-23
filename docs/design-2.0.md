<!-- @author kongweiguang -->

# XJar Plus 2.0 实施契约

本文记录已确认的 2.0 设计及并行实现边界，不代表所有能力已经通过验收。当前基线为 `b5d233c`，保留其中 Boot 资源与
Multi-Release 回归。

## 产品决定

- 三入口：Java 测试 main / CLI 可执行 JAR / 本地 Web UI，共用 core。
- 独立 JAR 只加密和密码启动；Go 启动器独占许可证、到期监督和 HTTP 状态职责。
- 新格式不兼容旧版；AES-256-GCM 是唯一内容加密算法。
- 工具 Java 21，format/runtime Java 8，Boot modern runtime Java 17。
- 包名前缀和 Maven groupId 均为 `io.github.kongweiguang.xjar.plus`，版本 `2.0.0`。
- 同 appId 的许可证跨构建复用，发行方凭据不得进入公共发布包。无效许可证拒绝运行，状态服务保留，续期重启生效。

## 模块与写入边界

format、runtime/runtime-boot-modern、core、app、launcher、验证/发布分别由独立执行者负责；主任务维护根构建和集成，不允许同时改同一区域。

## 共享 Java 协议接口

`format` 包下提供 JDK-only Java 8 类，最终字节协议由 format 实现者同步至 `docs/format-v2.md`：

- `Crypto`: `randomBytes(int)`、`seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext)`、`open(...)`、
  `derive(char[] password, byte[] salt, int iterations)`。
- `KeyEnvelope`: `create(char[] password)`、`parse(byte[])`、`encode()`、`unwrap(char[])`、`getArtifactId()`；创建结果以
  `getDataKey()` 仅供构建使用，不序列化明文数据钥。
- `NonceSequence`: 每次新数据钥独立随机 8-byte 前缀和 32-bit 不回绕计数器，`next()` 返回 12 bytes。
- `EntryMetadata`: 路径、是否加密、原长度、CRC32、SHA-256，getter；路径包含嵌套 JAR 前缀，例如 `BOOT-INF/lib/a.jar!/p/A.class`。
- `ArchiveIndex`: 基于条目集合构造，`get(String)`、`entries()`；`IndexCodec.seal(index,key,artifactId,nonces)` /
  `open(bytes,key,artifactId)`。
- `ChunkCipher.encrypt(in,out,key,artifactId,path,length,nonces)`；`decryptingStream(in,key,artifactId,path,length)`，1
  MiB 有界分块、空条目也认证，禁止认证前放出块明文。
- `Passwords.readFile(Path)`：严格 UTF-8，最多 64 KiB，只剥离一个末尾换行，不 trim、不做 Unicode 归一化。

归档保留条目：`META-INF/xjar-plus/envelope.bin`、`index.bin`、`original-manifest.mf`。新 Manifest 包含
`XJar-Plus-Version: 2`；运行时主类由归档家族决定，原主类由 original manifest 取得。core 必须将原 manifest 作为认证索引中的条目保护。

## Java 公共 API

`api.XJarPlus` 静态门面：`inspect(Path): ArchiveInfo`、`encrypt(EncryptionRequest): BuildResult`、
`decrypt(EncryptionRequest): BuildResult`、`verify(Path,char[]): ArchiveInfo`、
`packageLauncher(LauncherRequest): BuildResult`、`createIssuer(Path,String): BuildResult`、
`issueLicense(LicenseRequest): BuildResult`。

- EncryptionRequest builder：input/output (Path)、password (char[])、include/exclude (String，可重复)、encryptDependencies
  (boolean)、progress (ProgressListener)、cancellation (CancellationToken)。默认不覆盖输出。
- LauncherRequest builder：encryption (EncryptionRequest)、output (Path目录)、target (String:
  windows/linux/darwin-amd64/arm64)、issuerFile (Path)、appId (String)、jdkZip (Path可空)、goExecutable (Path可空，默认PATH)
  、jvmArg/appArg (String可重复)、notBefore/notAfter (Instant，后者可空表示永久)。
- LicenseRequest builder：issuerFile/output (Path)、notBefore/notAfter (Instant)。
- BuildResult：output (Path)、artifactId (String，可空)、warnings (List<String>)。
- ArchiveInfo：kind (String: PLAIN/BOOT_CLASSIC/BOOT_MODERN/SOLON)、mainClass (String)、encrypted (boolean)、entries (int)
  、minimumJava (Integer，可空)。无密码 inspect 加密归档不能推导原始 class 版本，返回未知；verify 认证后再给出真实下限，不能把密文
  magic 误当成 Java 8。
- ProgressListener：`onProgress(String stage,int percent)`；CancellationToken：`isCancelled()`、`check()`，默认 NONE。

## Go / Java 分发契约

Go module 位于 `launcher`，入口 `./cmd/launcher`。Java 将整个可构建 module 复制到私有暂存区，只生成
`cmd/launcher/payload/config.json`、`app.jar`、可选 `jdk.zip`；使用参数列表执行 `go build -o <binary> ./cmd/launcher`。

config.json
字段：version=2、appId、artifactId、dataKey/base64、licenseKey/base64、embeddedJdk/boolean、minimumJava/int、jvmArgs/string[]
、appArgs/string[]。minimumJava 表示归档 base class 与适配器要求的最低 Java feature，用于在供钥前验证目标 JVM；不将工具的
Java 21 要求强加给 Java 8 应用。只在私有构建区和最终离线启动器内存在，不进入日志或公共 Release。

发行凭据 `.issuer`：ASCII magic `XJI2` + uint32大端 UTF-8 appId长度 + appId + 32-byte licenseKey。许可证 `key.x`：magic
`XJL2` + 12-byte random nonce + AES-GCM (ciphertext||tag)，AAD 固定 UTF-8 `xjar-plus/license/v2`；明文为 uint32大端
appId长度 + appId + int64大端 notBefore/notAfter (UTC epoch seconds，notAfter=0永久)。无附加尾部，appId最多256 UTF-8
bytes。所有解析器限制长度并拒绝未知或多余字段。

供钥独立于 HTTP 状态：TCP 127.0.0.1 随机端口，30秒存活。子进程专用环境 `XJAR_PLUS_BROKER_PORT`、`XJAR_PLUS_BROKER_TOKEN`
(32-byte随机token的hex)、`XJAR_PLUS_ARTIFACT_ID`。请求 UTF-8一行 `<token> <artifactId>\n`(最多256 bytes)
，成功响应恰好32-byte数据钥随后EOF；成功只允许一次，失败/超时/子进程退出关闭。真实密钥与口令不进入 argv、环境或临时秘密文件，业务
stdin 原样继承。

运行时通过 `-Dxjar.plus.password-file=<path>` 或 Console 获取密码；检测到 broker 环境但握手失败不能回退到其他密码源。不得读取
System.in。

## 实施与验证

先建立新模块、协议、运行时、core 和
UI/CLI；新链路验证通过再删除旧实现。测试覆盖真实原包/加密包/恢复包、认证破坏、资源元数据、MR、授权失败与到期、进程树和输出事务。框架矩阵锁定版本，编译成功不等于跨平台真实运行成功。提交、推送、发布均未获授权。
