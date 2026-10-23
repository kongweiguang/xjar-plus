<!-- @author kongweiguang -->

# 命令与 API

以已安装版本的 `--help` 为准；下列契约适用于 2.0.0。示例路径是占位符，不代表允许覆盖现有文件。

## 独立加密 JAR

```text
java -jar xjar-plus-2.0.0.jar doctor
java -jar xjar-plus-2.0.0.jar inspect --input app.jar
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output encrypted.jar --password-file password.txt
java -jar xjar-plus-2.0.0.jar verify --input encrypted.jar --password-file password.txt
java "-Dxjar.plus.password-file=password.txt" -jar encrypted.jar
java -jar xjar-plus-2.0.0.jar decrypt --input encrypted.jar --output restored.jar --password-file password.txt
```

工具命令可用 `--password-stdin` 替代文件选项；都不提供时需要交互式控制台。加密应用只从控制台或上述 JVM 属性读取密码，不占用业务标准输入。密码文件必须为严格 UTF-8、最多 64 KiB；只去除最后一个换行，其余空格和 Unicode 字符均参与密码派生。

`encrypt` 和 `package` 支持重复 `--include`、`--exclude`。依赖默认不修改，需要明确指定 `--encrypt-dependencies`。`decrypt` 总是还原完整归档，不接受过滤参数。

## Go 授权启动器

```text
java -jar xjar-plus-2.0.0.jar license init --app-id demo --output demo.issuer
java -jar xjar-plus-2.0.0.jar package --input app.jar --output dist --password-file password.txt --app-id demo --issuer-file demo.issuer --target windows-amd64
java -jar xjar-plus-2.0.0.jar license issue --issuer-file demo.issuer --output renewed-key.x --not-after 2027-12-31T16:00:00Z
```

目标值：`windows-amd64`、`windows-arm64`、`linux-amd64`、`linux-arm64`、`darwin-amd64`、`darwin-arm64`。macOS 的 Apple Silicon 使用 `darwin-arm64`，Intel 使用 `darwin-amd64`；交叉编译不等于实机验收。

`--jdk-zip jdk.zip` 必须匹配目标系统/架构；`--go-executable <path>` 可指定 Go 编译器。`--not-before`、`--not-after` 使用 ISO-8601 UTC 时间，不设置到期时间即永久授权。以连字符开头的参数使用 `--jvm-arg=-Xmx512m`、`--app-arg=--server.port=8080`，每个参数重复一次选项。

发行凭据保密并持续复用；只将生成的分发目录交给客户。经用户明确授权部署后，保留旧许可证恢复副本，把新许可证放到启动器配置的 `key.x` 位置并重启。检查本机状态端口的 `/xjp/license/status` 和 `/xjp/healthz`，区分授权有效、业务启动/运行/停止及过期状态。

## Java main() 与本地网页

Java 21 引入 `io.github.kongweiguang.xjar.plus:xjar-plus-core:2.0.0`。库由 SDK 或本地 `mvn install` 提供，不假定已发布 Maven Central。SDK 安装脚本为 `install_sdk.py`，需要 Python 3.11+；Windows 通常用 `python install_sdk.py`，macOS/Linux 通常用 `python3 install_sdk.py`，不依赖 PowerShell。

```java
try (EncryptionRequest request = EncryptionRequest.builder()
        .input(Path.of("app.jar"))
        .output(Path.of("encrypted.jar"))
        .password(passwordReadLocally)
        .build()) {
    final BuildResult result = XJarPlus.encrypt(request);
    XJarPlus.verify(result.output(), passwordReadLocally);
}
```

请求、结果和门面从 `io.github.kongweiguang.xjar.plus.api` 导入。调用方在 `finally` 清除自己持有的密码数组；关闭请求会清除其防御性副本。`LauncherRequest` 对应 `XJarPlus.packageLauncher`，`LicenseRequest` 对应 `XJarPlus.issueLicense`。

SDK 提供完整的 `EncryptMain.java`、`PackageMain.java`、`IssuerMain.java`、`LicenseMain.java`，无需用户修改工具实现类。

```text
java -jar xjar-plus-2.0.0.jar ui
java -jar xjar-plus-2.0.0.jar ui --no-browser
```

网页仅本机可用，并需要会话令牌。排障不能禁用 Host/Origin/token 校验来迁就远程请求。系统选择器不可用时手动填写路径；配置导出不含密码、发行凭据路径或可能含秘密的 JVM/业务参数。
