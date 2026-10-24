<!-- @author kongweiguang -->

# Java API 使用指南

本文说明如何在 Java 21 项目中调用 XJar Plus 2.0.0 的公开 API。API 位于
`io.github.kongweiguang.xjar.plus.api`；不需要依赖 CLI、修改 XJar Plus 源码或直接调用内部实现类。
命令行参数和 glob 筛选规则另见[命令参考](commands.md)。

## 1. 准备 SDK

### 使用发布 SDK

从项目的 GitHub Release 下载 `xjar-plus-2.0.0-sdk.zip` 并解压。安装脚本会把 parent POM、四个库模块的 POM、二进制 JAR 和源码 JAR
安装到当前用户的本地 Maven 仓库；它不会发布到 Maven Central 或远程仓库。

- Windows：在解压目录运行 `python install_sdk.py`
- macOS/Linux：在解压目录运行 `python3 install_sdk.py`

需要 Python 3.11+ 和可从 `PATH` 调用的 Maven。若 Maven 不叫 `mvn`，可用
`python install_sdk.py --maven-command <Maven 可执行文件>` 指定。

### 从仓库源码安装

在 XJar Plus 仓库根目录执行 `mvn -DskipTests install`，将 parent POM 和所需模块安装到本地 Maven 仓库。该操作不是远程发布。

在自己的 Maven 项目中加入：

```xml
<dependency>
  <groupId>io.github.kongweiguang.xjar.plus</groupId>
  <artifactId>xjar-plus-core</artifactId>
  <version>2.0.0</version>
</dependency>
```

`xjar-plus-core` 传递依赖所需的格式与运行时模块。工具端使用 Java 21；将 API 放到 `src/test/java` 并从 IDE 运行时，测试运行配置也要使用 Java 21。
它加密或打包的应用按其实际字节码与框架运行时要求选择目标 Java 版本。

SDK 解压后也附有四个完整示例：`EncryptMain.java`、`PackageMain.java`、`IssuerMain.java`、`LicenseMain.java`。可在 SDK 目录直接执行，例如：

```text
java -cp xjar-plus-2.0.0.jar examples/EncryptMain.java app.jar encrypted.jar private/password.txt
java -cp xjar-plus-2.0.0.jar examples/IssuerMain.java demo private/demo.issuer
java -cp xjar-plus-2.0.0.jar examples/PackageMain.java app.jar dist private/password.txt private/demo.issuer demo windows-amd64
java -cp xjar-plus-2.0.0.jar examples/LicenseMain.java private/demo.issuer renewed-key.x 2027-12-31T16:00:00Z
```

这些路径是示例参数。用真实应用时，应将密码文件和发行凭据放到受控的私有目录，并将输出路径改成尚不存在的新路径。

## 2. 可从 IDE 运行的加密示例

在项目 `src/test/java/io/github/kongweiguang/xjar/plus/examples/EncryptMain.java` 创建下列类。IDE 运行配置传入三个参数：原始 JAR、新的加密 JAR、私有密码文件。
此示例与 SDK 附带的 `examples/EncryptMain.java` 保持相同调用方式。

```java
/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.examples;

import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import io.github.kongweiguang.xjar.plus.format.Passwords;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * 保持为独立源码示例，也可直接放入项目的测试源码目录。
 */
public final class EncryptMain {
    /**
     * 仅保留可执行入口，避免把示例误用为可复用服务。
     */
    private EncryptMain() {
    }

    /**
     * 通过文件路径读取口令，避免将口令字面量提交到版本库。
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: EncryptMain <input.jar> <output.jar> <password-file>");
        }
        final char[] password = Passwords.readFile(Path.of(args[2]));
        try (EncryptionRequest request = EncryptionRequest.builder()
                .input(Path.of(args[0]))
                .output(Path.of(args[1]))
                .password(password)
                .progress((stage, percent) -> System.out.printf("%3d%% %s%n", percent, stage))
                .build()) {
            final var result = XJarPlus.encrypt(request);
            XJarPlus.verify(result.output(), password);
            System.out.println(result.output());
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
```

`Passwords.readFile` 严格按 UTF-8 读取、最多 64 KiB，只剥离末尾一个换行序列；不会 trim 空格或归一化 Unicode。`password(char[])` 会复制调用方数组，
请求关闭时清理其内部副本；调用方仍需在 `finally` 中清理自己持有的数组。请求使用 try-with-resources 关闭。

若不想使用该辅助读取器，可从控制台或密钥管理系统取得 `char[]`，按同样的清理规则传给 `.password(password)`；不要把密码放在源码常量、
JVM 参数、日志、CI 输出或普通配置导出中。不要把 `String` 当长期密码存储，因为它不可变且无法由调用方清零。

## 3. 公开 API 与结果

第 2 节是完整类；下面各节是放入现有 `main()` 或自己服务方法内的代码片段，示例路径仍需替换。IDE 可自动导入类型；手动添加时，除了完整例子已有的 import，按片段需要补充：

| 使用的类型 | 完整类型名 |
|---|---|
| `ArchiveInfo` | `io.github.kongweiguang.xjar.plus.api.ArchiveInfo` |
| `BuildResult` | `io.github.kongweiguang.xjar.plus.api.BuildResult` |
| `LicenseRequest` | `io.github.kongweiguang.xjar.plus.api.LicenseRequest` |
| `LauncherRequest` | `io.github.kongweiguang.xjar.plus.api.LauncherRequest` |
| `CancellationToken` | `io.github.kongweiguang.xjar.plus.api.CancellationToken` |
| `Instant` | `java.time.Instant` |
| `AtomicBoolean` | `java.util.concurrent.atomic.AtomicBoolean` |
| `CancellationException`（需要单独捕获取消时） | `java.util.concurrent.CancellationException` |

`XJarPlus` 是静态门面，常用调用如下：

| 方法 | 用途 | 返回值 |
|---|---|---|
| `inspect(Path input)` | 检查 JAR 结构，不读取密码 | `ArchiveInfo` |
| `encrypt(EncryptionRequest request)` | 加密并写到新的 JAR 路径 | `BuildResult` |
| `verify(Path input, char[] password)` | 认证加密内容，不写出明文恢复包 | `ArchiveInfo` |
| `decrypt(EncryptionRequest request)` | 完整恢复原始归档内容到新 JAR | `BuildResult` |
| `createIssuer(Path output, String appId)` | 首次创建发布者私有凭据 | `BuildResult` |
| `issueLicense(LicenseRequest request)` | 从已有凭据签发许可证 | `BuildResult` |
| `packageLauncher(LauncherRequest request)` | 加密应用并构建指定平台 Go 启动器 | `BuildResult` |

`ArchiveInfo` 提供 `kind()`（`PLAIN`、`BOOT_CLASSIC`、`BOOT_MODERN` 或 `SOLON`）、`mainClass()`、`encrypted()`、`entries()` 和
`minimumJava()`。主类可以为 `null`；`entries()` 统计全部 ZIP 条目（包括目录和注入的元数据/运行时），不等于选中的业务文件数。对加密归档，`inspect` 不认证密文，因此最低 Java 版本未知时
`minimumJava()` 为 `null`；先调用 `verify` 成功后，返回值包含从已认证字节码推导的最低版本。

`BuildResult.output()` 是已经提交的最终文件或启动器程序路径；启动器打包时它是可执行文件路径，不是传入的输出父目录。
`artifactId()` 在操作有格式层产物标识时返回该值，否则可能为 `null`。`warnings()` 是不可变的非致命提示，调用方应显示或记录，
不能在成功返回时丢弃。

归档操作可能抛出 `IOException`（输入、格式、认证或文件操作失败）；无效构建参数会在构建器或操作入口抛出 `IllegalArgumentException`。
取消通过 `CancellationException` 表示，是未检查异常。失败结果不会作为 `BuildResult` 返回。

## 4. 加密请求和筛选范围

`EncryptionRequest.builder()` 的输入字段如下：

| Builder 方法 | 必需 | 默认值和约束 |
|---|---:|---|
| `input(Path)` | 是 | 可读的可执行 JAR；必须有受支持的入口与布局。只支持普通 JAR、Boot classic/modern 和 Solon 标准可执行布局。 |
| `output(Path)` | `encrypt`、`decrypt` 必需 | 新目标路径；`packageLauncher` 不使用此字段，打包器使用自己的私有暂存路径和 `LauncherRequest.output`。 |
| `password(char[])` | 是 | 1..65536 个 UTF-16 代码单元；拷贝传入数组，调用方和请求各自负责清理副本。文件输入另受 64 KiB UTF-8 限制。 |
| `include(String)` | 否 | 可重复；Java 文件系统 `glob:` 模式，匹配 ZIP/JAR 条目的完整归档路径。 |
| `exclude(String)` | 否 | 可重复；任一排除规则命中都优先于包含规则。 |
| `encryptDependencies(boolean)` | 否 | 默认 `false`；设为 `true` 才把支持布局中的嵌套依赖纳入筛选。 |
| `progress(ProgressListener)` | 否 | 默认空操作；回调接收阶段名和 0–100 的有界整数百分比。 |
| `cancellation(CancellationToken)` | 否 | 默认 `CancellationToken.NONE`；传入可协作取消的信号。 |

构建器一次只构造一个请求；`build()` 要求输入路径和密码，并在构建后清理构建器持有的密码副本。请求关闭时清理它自己的副本。直接 `encrypt` 与 `decrypt` 都必须设置
新的 `output`；`packageLauncher` 使用 `LauncherRequest.output` 作为发行父目录，不读取 `EncryptionRequest.output`。

必须按**归档中的实际路径**写 glob，不会把 Java 包名转换成路径，也不会自动去掉 `BOOT-INF/classes/` 前缀。普通 JAR 可用
`com/acme/**`；Spring Boot 和本仓库验证的 Solon 标准嵌套布局需用 `BOOT-INF/classes/com/acme/**`。先用 `jar tf app.jar` 确认；其他位置按实际条目填写。跨布局需要时重复 include。完整语义和示例见
[指定包名、类和资源](commands.md#selection)。

例如，只加密 Boot 应用的 `com.example` 包，排除其中的公开 API 子树：

```java
EncryptionRequest request = EncryptionRequest.builder()
        .input(Path.of("app.jar"))
        .output(Path.of("app-encrypted.jar"))
        .password(password)
        .include("BOOT-INF/classes/com/example/**")
        .exclude("BOOT-INF/classes/com/example/publicapi/**")
        .build();
```

多个 include 是 OR；排除匹配始终优先。未命中的条目仍保留在归档中，不会被删除；索引仍保护归档内容完整性。嵌套依赖默认原样保留；要筛选某个嵌套 JAR，
先设置 `.encryptDependencies(true)`，再用归档路径表达容器和内部路径，例如 `BOOT-INF/lib/business.jar!/com/acme/**`。这不改变不在筛选范围内的依赖。

外层输入含签名元数据时，加密会拒绝修改，因为替换启动信息和注入运行时会使原签名失效。默认原样保留的签名嵌套依赖不受影响；如果显式启用依赖加密且筛选命中
该依赖、需要重写它，操作会拒绝，不会静默删签名或宣称旧签名仍有效。

`decrypt` 有完整恢复语义：它不接受非空 include/exclude 或 `encryptDependencies(true)`，也不会只恢复所选条目。恢复会把原归档全部条目写到新输出路径；
重封装后的 ZIP 字节可能与输入不同，但条目内容会被认证和恢复。

## 5. 查看、验证与恢复

### 仅查看结构

```java
ArchiveInfo info = XJarPlus.inspect(Path.of("app-encrypted.jar"));
System.out.println("layout=" + info.kind() + ", encrypted=" + info.encrypted());
if (info.minimumJava() == null) {
    System.out.println("最低 Java 版本尚未通过 verify 认证确认");
} else {
    System.out.println("minimumJava=" + info.minimumJava());
}
```

`inspect` 不需要密码，也不认证条目；不要把“能 inspect”当成“密文正确”。

### 验证密码和完整密文

```java
final char[] password = Passwords.readFile(Path.of("private/password.txt"));
try {
    final ArchiveInfo verified = XJarPlus.verify(Path.of("app-encrypted.jar"), password);
    System.out.println("已验证 " + verified.entries() + " 个条目；最低 Java=" + verified.minimumJava());
} finally {
    Arrays.fill(password, '\0');
}
```

`verify` 会认证密钥信封、索引以及所有受保护内容，不生成解密后的归档文件。错误密码、损坏的索引或篡改内容会失败关闭，不回退为明文读取。
调用后始终清理传入的 `char[]`。

### 解密恢复完整 JAR

```java
final char[] password = Passwords.readFile(Path.of("private/password.txt"));
try (EncryptionRequest request = EncryptionRequest.builder()
        .input(Path.of("app-encrypted.jar"))
        .output(Path.of("app-restored.jar"))
        .password(password)
        .build()) {
    final BuildResult restored = XJarPlus.decrypt(request);
    System.out.println(restored.output());
} finally {
    Arrays.fill(password, '\0');
}
```

`decrypt` 认证并恢复全部内容；输入和输出必须是不同路径，输出文件必须不存在。不要把输入加密文件作为输出覆盖。

## 6. 发布者凭据与许可证

### 首次初始化发行凭据

同一个 `appId` 只初始化一次发行凭据，并把文件存放在发布者私有、安全备份的位置：

```java
final BuildResult issuer = XJarPlus.createIssuer(Path.of("private/demo.issuer"), "demo");
System.out.println(issuer.output());
```

该文件是后续打包和续期所需的发布者秘密，不能交付客户、提交 Git、放进 SDK 或公共附件。路径必须是新的，已有文件不会覆盖。`appId` 需要 1–256 字节严格 UTF-8，
按字节精确绑定，不折叠大小写或归一化 Unicode；空值或空白值会被 API 拒绝。重复发行同一应用时复用原凭据和完全一致的 `appId`；不要为同一应用重复创建凭据。

### 签发或续期许可证

签发新文件并保留旧许可证作为回滚副本：

```java
final LicenseRequest request = LicenseRequest.builder()
        .issuerFile(Path.of("private/demo.issuer"))
        .output(Path.of("release/key.x"))
        .notBefore(Instant.parse("2027-01-01T00:00:00Z"))
        .notAfter(Instant.parse("2028-01-01T00:00:00Z"))
        .build();
final BuildResult license = XJarPlus.issueLicense(request);
System.out.println(license.output());
```

`LicenseRequest.Builder` 必须设置 `issuerFile(Path)` 和新的 `output(Path)`；`notBefore(Instant)` 默认是创建 builder 时的当前时刻，
`notAfter(Instant)` 默认 `null`，表示永久有效。生效边界包含在有效期内，到期边界不包含；结束时刻不能早于生效时刻。请求对象只持有文件路径和时间，不缓存密钥字节，
无需关闭。已运行的 Go 启动器使用启动时加载的许可证；替换旁边的 `key.x` 后必须重启才能使用新证。

## 7. 构建 Go 授权启动器

先使用同一份 `appId` 和发行凭据创建新分发包。`LauncherRequest.Builder` 的字段如下：

| Builder 方法 | 必需 | 默认值和约束 |
|---|---:|---|
| `encryption(EncryptionRequest)` | 是 | 与独立加密共用输入、密码、筛选和进度策略；`EncryptionRequest.output` 可省略。LauncherRequest 关闭时也关闭该归档请求。 |
| `output(Path)` | 是 | 新分发的父目录；构建结果的 `output()` 才是可执行文件的最终路径。 |
| `issuerFile(Path)` | 是 | 已创建的私有发行凭据，必须与 appId 相配。 |
| `appId(String)` | 是 | 1–256 字节严格 UTF-8 标识；同一应用后续版本和续期复用，不做大小写折叠或 Unicode 归一化。 |
| `target(String)` | 否 | 默认 `windows-amd64`；生产构建建议显式设置。仅支持 `windows-amd64`、`windows-arm64`、`linux-amd64`、`linux-arm64`、`darwin-amd64`、`darwin-arm64`。 |
| `jdkZip(Path)` | 否 | 默认不内嵌 JDK；有值时会在构建前检查其系统、架构和 Java 版本。省略时目标机器须提供适用 Java。 |
| `goExecutable(Path)` | 否 | 默认从 `PATH` 查找 Go 编译器；打包要求 Go 1.24+。目标机运行启动器不需要 Go。 |
| `jvmArg(String)` | 否 | 可重复；每次追加一个 JVM argv，不拼接 shell 字符串。 |
| `appArg(String)` | 否 | 可重复；每次追加一个应用 argv，不拼接 shell 字符串。 |
| `notBefore(Instant)` | 否 | 默认为 builder 创建时刻，生效边界包含。 |
| `notAfter(Instant)` | 否 | 默认 `null`，表示永久授权；如设置，到期边界不包含。 |

完整打包示例：

```java
final char[] password = Passwords.readFile(Path.of("private/password.txt"));
try {
    try (EncryptionRequest encryption = EncryptionRequest.builder()
            .input(Path.of("app.jar"))
            .password(password)
            .include("BOOT-INF/classes/com/example/**")
            .progress((stage, percent) -> System.out.printf("%3d%% %s%n", percent, stage))
            .build();
         LauncherRequest request = LauncherRequest.builder()
                 .encryption(encryption)
                 .output(Path.of("dist"))
                 .issuerFile(Path.of("private/demo.issuer"))
                 .appId("demo")
                 .target("linux-amd64")
                 .jdkZip(Path.of("private/jdk-linux-amd64.zip"))
                 .jvmArg("-Xmx512m")
                 .appArg("--label=含 空格")
                 .notBefore(Instant.parse("2027-01-01T00:00:00Z"))
                 .notAfter(Instant.parse("2028-01-01T00:00:00Z"))
                 .build()) {
        final BuildResult result = XJarPlus.packageLauncher(request);
        System.out.println("启动器：" + result.output());
        result.warnings().forEach(System.err::println);
    }
} finally {
    Arrays.fill(password, '\0');
}
```

`LauncherRequest.Builder.encryption` 保存传入的同一个 `EncryptionRequest`，不是另造请求副本；关闭 `LauncherRequest` 会关闭其内嵌的加密请求。上例同时将二者放在
try-with-resources 中，`EncryptionRequest.close()` 幂等，所以退出时重复关闭安全。`packageLauncher` 同步执行整个构建；不要把密码数组提前清零到调用结束之前。

输出父目录下的最终程序路径由 `BuildResult.output()` 返回；同一目标已存在时拒绝覆盖。构建先验证 JAR、目标和可选 JDK，再在私有暂存区生成并验证分发件；失败或取消不会留下可误认为成功的半成品。
获取结果后还应查看 `warnings()`，并在目标系统执行真实启动验证。交叉编译成功只证明生成步骤通过，不代表目标机器上的 Java、权限或应用行为都已验收。

## 8. 进度、协作取消和失败处理

所有 API 方法同步执行。`ProgressListener` 是粗粒度回调，函数形状为 `onProgress(String stage, int percent)`；百分比被限制在 0–100，阶段名称面向用户，
不承诺每个条目都单独回调。它和 `CancellationToken` 通过 `EncryptionRequest` 提供，适用于 `encrypt`、`decrypt` 和 `packageLauncher` 中的归档处理；
`inspect`、`verify`、`createIssuer`、`issueLicense` 没有单独的进度或取消参数。UI 或服务可以在工作线程运行这些同步 API，并将进度映射到自身界面。

默认 `CancellationToken.NONE` 不会取消。长任务可连接一个线程安全标志；调用方从取消按钮或其他线程更新标志。下面代码是现有 API 调用中的片段，
其中 `input`、`output` 和 `password` 是调用方已经准备好的变量：

```java
final AtomicBoolean cancelRequested = new AtomicBoolean();
final CancellationToken cancellation = new CancellationToken() {
    /**
     * 在安全的归档边界读取外部取消请求，不强制中断正在写入的底层操作。
     */
    @Override
    public boolean isCancelled() {
        return cancelRequested.get();
    }
};

try (EncryptionRequest request = EncryptionRequest.builder()
        .input(input)
        .output(output)
        .password(password)
        .cancellation(cancellation)
        .build()) {
    XJarPlus.encrypt(request);
    // 取消端可从其他线程执行：cancelRequested.set(true);
} finally {
    Arrays.fill(password, '\0');
}
```

取消是协作式的：操作会在安全检查点停止并以 `CancellationException` 结束，不保证在任意一条底层读写指令中瞬时停止。使用 try-with-resources 关闭请求，
并在 `finally` 清理自己持有的密码数组。若取消或处理异常，输出事务会清理暂存内容；调整原因后可换一个新输出路径重试。

操作可能因为无效 JAR/签名、目标不支持、认证失败、输出已存在、权限或磁盘错误而失败。保留异常原因供本地诊断，但不要把密码、发行凭据、完整敏感参数或含秘密的输入输出上下文写入公共日志。失败时先检查输出目录，不要把暂存目录交付为成功产物。

## 9. 对应关系和边界

| 场景 | Java API | CLI |
|---|---|---|
| 加密独立 JAR | `XJarPlus.encrypt(EncryptionRequest)` | `encrypt` |
| 查看结构 | `XJarPlus.inspect(Path)` | `inspect` |
| 认证密文 | `XJarPlus.verify(Path, char[])` | `verify` |
| 完整恢复 | `XJarPlus.decrypt(EncryptionRequest)` | `decrypt` |
| 创建发行凭据 | `XJarPlus.createIssuer(Path, String)` | `license init` |
| 签发许可证 | `XJarPlus.issueLicense(LicenseRequest)` | `license issue` |
| 构建授权启动器 | `XJarPlus.packageLauncher(LauncherRequest)` | `package` |

两种入口共用核心规则和输出事务。独立加密 JAR 不实施许可证或到期限制；需要启动器授权监督时，使用 Go 启动器模式。AES 加密和本地许可证提高静态内容保护门槛，
不能保证抵御控制运行机器的人提取密钥、修改程序或回拨系统时间。

完整命令、密码输入、目标平台和运行方法见[命令参考](commands.md)；文件选择、任务取消和配置导入导出见[网页手册](web-ui.md)。
