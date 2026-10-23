# Spring Boot 4 静态资源修复与验证

验证日期：2026-10-23。运行环境：Windows 11 x64，Liberica JDK 21.0.7 / 25.0.2，Go 1.24.3。

## 修复内容

`XBootURLConnection` 原先返回解密后的正文，但仍暴露 ZIP 中密文的长度。20 字节的文件会声明 `Content-Length: 32`，导致客户端等待剩余正文或下载失败。另一个问题是 JDK `CipherInputStream.skip()` 只能跳过已经解密的缓冲，首次跳过偏移可能返回 0，导致 Spring 的后缀 Range 请求失败。

本次修复：

- 对独立的 ZIP 条目流解密扫描，得到明文长度与 CRC；长度 API、headers 和 `JarEntry` 返回一致的明文信息。
- 只有完整读取成功后才缓存元信息；失败时长度返回未知，读取仍报告异常。
- 不修改底层共享 ZIP 条目，不消费响应流，不提前关闭无缓存连接使用的 `JarFile`。
- `getContent()` 通过当前解密连接读取；解密初始化失败会关闭原始流。
- 用最多 8 KiB 的缓冲读取并丢弃明文，实现精确 `skip()`，支持跨加密块和跨缓冲的 Range 偏移。
- Boot 类加载器与 URL handler 按运行时真正选中的物理条目判断是否解密，保留依赖包维度，支持多版本 JAR 中没有基础版本的类，以及基础版与版本版采用不同加密规则的情况。

长度扫描额外占用固定内存，只缓存长度与 CRC，不缓存完整明文，也不写出明文文件。代价是每个连接首次查询长度时需要完整扫描资源，HEAD 和 Range 也可能触发该扫描。

## 验证结果

| 验证项目 | 结果 |
| --- | --- |
| Maven 构建与单元测试 | 19 项通过，0 失败、0 错误、0 跳过 |
| Boot 4.0.9 + JDK 21 / 25 | 两种 JDK 下，原包、4 种加密范围、每种范围的解密还原运行均通过 |
| Boot 4.1.2 + JDK 21 / 25 | 两种 JDK 下，原包、4 种加密范围、每种范围的解密还原运行均通过 |
| Boot 4.0.9 Windows x64 分发 | 全量加密，携带 JDK 21，实际运行通过 |
| Boot 4.1.2 Windows x64 分发 | 全量加密，携带 JDK 21，实际运行通过 |

四种加密范围分别为业务类、业务类和资源、额外包含依赖内类、`**` 全量加密。测试检查目标条目确实变成密文、目标 Boot loader 保留原样、嵌套依赖保持 STORED；每种范围均验证错误密码无法启动，并比较还原后的 manifest、全部业务条目和全部原始依赖条目。每版包含 34 个依赖包，4.0.9 对照 11186 个依赖条目，4.1.2 对照 11211 个。依赖 manifest 按主属性及命名属性比较，其余条目按字节比较：项目现有 manifest 读写会调整部分依赖的折行（如 SnakeYAML），已确认属性完全相同。

19 项单元测试包括 3 项归档路由、10 项资源连接及偏移读取、6 项多版本类和资源测试；后者额外覆盖中文空格路径、跨依赖同名资源、连接配置生命周期及普通目录加载。

另完成 Boot 3.4.2 + JDK 21 的四种范围基础回归：控制器、配置、普通资源、静态 GET、错误密码和还原后运行均通过。

JDK 25 的扩展验收曾发现 Spring 的版本专用 `ClassFileMetadataReaderFactory` 被当成明文加载的问题，已加入本次修复与多版本 JAR 回归范围。版本选择遵循实际 `JarEntry.getRealName()`，不自行猜测应选哪个 Java 版本。参见 [JDK JarEntry 文档](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/jar/JarEntry.html)。

真实 HTTP 测试使用 0、16、20、131089 字节四份静态文件，逐字节比较正文，并验证：

- GET 与 HEAD 的明文 `Content-Length`，20 字节文件现在声明并返回 20 字节。
- 前缀、后缀与多段 Range 的 206、正文及 `Content-Range`；越界请求返回 416。
- Last-Modified 和 ETag 条件请求返回无正文的 304，不匹配的 ETag 返回完整正文。
- `URLConnection` 长度、CRC、headers、重复读取、普通及带类型的 `getContent()`。
- 八个并发 GET 的正文与长度；所有自建进程退出、监听端口关闭。

正常静态文件使用 Boot 自带资源处理链，没有加入缓存响应正文的 filter。单独的 `/etag/**` 映射配置 SHA-256 ETag 生成器，以验证加密资源在启用 ETag 时的行为。原包在过期 `If-Range` 下仍返回 206；加密包和还原包与原包一致，本次未改变框架行为。

Windows 分发测试先从生成的 `main.go` 定位实际嵌入的加密 JAR，直接验证四份静态文件、业务类及依赖类均为密文，然后只复制 `main.exe` 和 `key.x` 到中文及含空格目录，清除子进程的 `JAVA_HOME`、`GOROOT`，PATH 仅保留系统目录。测试确认业务 JVM 来自解包的内嵌 JDK，并验证授权状态与健康接口及退出清理。

## 复现

需要 JDK 21、Maven；Windows 分发测试还需要 Go 和根目录直接包含 `bin/`、`conf/`、`lib/` 的 Windows JDK zip。以下命令在仓库根目录的 PowerShell 执行。

```powershell
mvn -B -ntp package
if ($LASTEXITCODE -ne 0) { throw '工具构建失败' }
$verifyRoot = New-Item -ItemType Directory -Force target/boot4-verification-rerun
$verifyClasspath = 'target/xjar-plus-1.0.jar;target/test-classes'

foreach ($bootVersion in @('4.0.9', '4.1.2')) {
    mvn -B -ntp -f src/test/fixtures/spring-boot/pom.xml "-Dboot.version=$bootVersion" package
    if ($LASTEXITCODE -ne 0) { throw "Boot $bootVersion 测试应用构建失败" }
    $appJar = Join-Path $verifyRoot.FullName "app-$bootVersion.jar"
    Copy-Item src/test/fixtures/spring-boot/target/app.jar $appJar
    java -cp $verifyClasspath verification.SpringBootEncryptionCheck $appJar $verifyRoot.FullName $bootVersion
    if ($LASTEXITCODE -ne 0) { throw "Boot $bootVersion 加解密验证失败" }
}
```

测试应用的 JAR 插件强制重新生成普通 JAR，防止切换版本后错误复用上一版 Boot 的打包产物。验证入口还会断言 manifest 中的实际 Boot 版本。

JDK 25 验证使用相同 fixture 和入口，显式替换调用方 JVM；入口会用该 JVM 运行所有业务子进程：

```powershell
foreach ($bootVersion in @('4.0.9', '4.1.2')) {
    $appJar = Join-Path $verifyRoot.FullName "app-$bootVersion.jar"
    & 'C:/Java/jdk-25/bin/java.exe' -cp $verifyClasspath `
        verification.SpringBootEncryptionCheck $appJar $verifyRoot.FullName $bootVersion
    if ($LASTEXITCODE -ne 0) { throw "Boot $bootVersion JDK 25 验证失败" }
}
```

Windows 分发验证按本机位置替换 JDK zip 和 Go bin：

```powershell
foreach ($bootVersion in @('4.0.9', '4.1.2')) {
    $appJar = Join-Path $verifyRoot.FullName "app-$bootVersion.jar"
    $distributionRoot = Join-Path $verifyRoot.FullName "native-$bootVersion"
    java -cp $verifyClasspath verification.SpringBootLauncherCheck `
        $appJar C:/Java/jdk-21-windows-x64.zip C:/Go/bin $distributionRoot $bootVersion
    if ($LASTEXITCODE -ne 0) { throw "Boot $bootVersion Windows 分发验证失败" }
}
```

本次原始日志位于 `target/boot4-fixed-verification/`，测试源码和独立应用保存在 `src/test/java/verification/` 与 `src/test/fixtures/spring-boot/`。测试产物含一次性验证密码，仅用于本地验收。

最终日志：`project-build.log`、`project-final-test.log`、`jar-4.0.9.log`、`jar-4.0.9-jdk25.log`、`jar-4.1.2.log`、`jar-4.1.2-jdk25.log`、`native-4.0.9.log`、`native-4.1.2.log`、`boot3-regression.log`。最终测试使用的工具产物为 `target/xjar-plus-1.0.jar`，SHA-256：

```text
DB60DE5D5D9A4D21EAAB1E421C23DFA8267265576030B054A2CEEA8BD491257F
```

## 使用范围

以上证明已测试版本的默认 `org.springframework.boot.loader.launch.JarLauncher` JVM 可执行 JAR 可用。未覆盖所有 Spring Boot 4.x 补丁版本、WAR、`PropertiesLauncher`、自定义 loader、Native Image 或其他操作系统运行情况。

已有加密 JAR 和 Go 启动器需用修复后的工具重新生成；仅替换构建机器上的工具不能更新已分发产物中的解密代码。
