<!-- @author kongweiguang -->

# 从 main () 使用 Java API

这些代码既可放进自己的 `src/test/java` 后从 IDE 运行，也可用 Java 21 源文件模式运行。
不需要在 XJar Plus 的内部实现中改路径或密码。先准备一个只允许当前用户读取的 UTF-8 密码文件。

完整的安装、参数、筛选与进度取消说明见 [Java API 手册](https://github.com/kongweiguang/xjar-plus/blob/master/.agents/skills/xjar-plus/references/java-api.md)。只加密指定包时，先阅读 [归档路径与 glob 示例](https://github.com/kongweiguang/xjar-plus/blob/master/.agents/skills/xjar-plus/references/commands.md#selection)：普通包用 `com/example/**`，Spring Boot / 标准嵌套 Solon 用 `BOOT-INF/classes/com/example/**`，通过 `EncryptionRequest.Builder.include(...)` / `.exclude(...)` 逐条设置。

下载或构建 `xjar-plus-2.0.0.jar` 后，在包含该 JAR 和 `examples` 目录的位置执行：

```text
java -cp xjar-plus-2.0.0.jar examples/EncryptMain.java app.jar encrypted.jar password.txt
java -cp xjar-plus-2.0.0.jar examples/IssuerMain.java demo demo.issuer
java -cp xjar-plus-2.0.0.jar examples/PackageMain.java app.jar dist password.txt demo.issuer demo windows-amd64
java -cp xjar-plus-2.0.0.jar examples/LicenseMain.java demo.issuer renewed-key.x 2027-12-31T16:00:00Z
```

`IssuerMain` 只在应用第一次建立授权时运行；其输出必须放在发布者私有目录，已存在的文件不会覆盖。后续打包和续期复用该凭据，也可用
CLI 的 `license init` 完成同一操作。

`PackageMain` 最后可追加目标 `jdk.zip`；不追加时目标机器必须有适合业务应用的 Java。
默认许可证为永久；示例中的 `.notAfter(Instant)` 可以设置到期时间，边界是 UTC、到期时刻不再有效。
在自己的 `main()` 中，可在构建请求前用 `builder.jvmArg("-Xmx512m")`、`builder.appArg("--label=含 空格")` 追加参数；每次调用保留为一个
argv，不要自行加 shell 引号。`LauncherRequest.Builder` 和 `LicenseRequest.Builder` 都可用
`.notBefore(Instant.parse("2027-01-01T00:00:00Z"))` 指定含边界的生效时间；省略时默认为创建 builder
的当前时刻，未来生效的许可证在到时之前不会启动业务。
`.issuer` 是发行方的私密授权凭据，不发给客户、不提交 Git、不上传公共附件。
续期使用同一个 `.issuer`；在部署端保存旧 `key.x` 的恢复副本、替换新证后重启启动器。

在 Maven 项目中使用时添加 `io.github.kongweiguang.xjar.plus:xjar-plus-core:2.0.0`。
本仓库不自动发布 Maven Central，先在仓库运行 `mvn install` 或按 SDK 中说明安装配套 JAR/POM。
请求对象以 `Path`、`char[]`、`Instant` 和参数数组表达输入；不拼接 shell 命令。
