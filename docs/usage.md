<!-- @author kongweiguang -->

# XJar Plus 2.0 使用指南

本文面向把 XJar Plus 接入项目的开发者。示例路径和应用名均为占位值；运行前先替换，并先对可恢复的测试 JAR 验证。Windows 11
amd64 的 24 项真实归档兼容测试已通过，固定框架版本、候选哈希和平台边界以 [
`compatibility-matrix.json`](compatibility-matrix.json) 为准；本地验证不代表 GitHub 发布已完成。

## 1. 选择入口和产物

| 需求                                 | 入口                        | 产物                      |
|--------------------------------------|-----------------------------|---------------------------|
| 在项目测试源码中用代码执行加密或打包 | Java API，直接运行 `main()` | 独立加密 JAR 或 Go 启动器 |
| 在终端处理 JAR                       | CLI，可执行 JAR             | 独立加密 JAR 或 Go 启动器 |
| 不想先写代码或记命令                 | 显式启动本地 Web UI         | 独立加密 JAR 或 Go 启动器 |

**独立加密 JAR** 使用密码解锁，不实施许可证/到期限制。 **Go 启动器**负责许可证验证、到期监督、业务进程管理和状态接口；可内嵌目标
JDK，也可在目标机上查找 Java。目标机运行 Go 启动器不需要 Go。

工具 JAR 需要 Java 21。被保护应用的 Java 下限取决于其字节码与所选归档运行时；这不等于工具使用的 Java 版本。普通 JAR 已在
Java 8/11/17/21/25 实测；框架各使用矩阵指定的 JDK，不外推为所有 JDK 与框架版本组合都已验证。

## 2. 构建工具

构建机需要 JDK 21 和 Maven。只有生成 Go 启动器时才需要可用的 Go 工具链；是否需要网络访问取决于 Go module
依赖是否已缓存。先从仓库根目录构建：

```text
mvn -pl xjar-plus-app -am package
```

可执行 JAR 路径为 `xjar-plus-app/target/xjar-plus-2.0.0.jar`。查看总帮助以及任一子命令的参数：

```text
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar --help
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar package --help
```

发布流水线使用更完整的质量和集成门槛；本地构建成功或可执行 JAR 能启动，不代表框架矩阵已通过。GitHub 发布计划见本文末尾和仓库的
release workflow。

仓库维护脚本统一使用 Python 3.11+ 标准库，Windows、macOS、Linux 共用同一份脚本；macOS/Linux 通常使用 `python3` 命令。直接运行工具 JAR 不需要额外安装 Python 或 PowerShell。开发时可执行：

```text
mvn -Pquality verify
python scripts/check_source.py
python scripts/check_architecture.py
python scripts/check_go_format.py
python scripts/verify_compatibility.py --framework all --java8 <java8> --java11 <java11> --java17 <java17> --java21 <java21> --java25 <java25>
python integration-tests/security/run.py --java8-home <jdk8>
python scripts/verify_launcher.py --target windows-amd64 --java-executable <java21> --go-executable <go>
python scripts/release.py --version 2.0.0 --output-directory target/release/assets
```

路径带空格时在终端加引号。各脚本的 `--help` 说明可选参数；`--build-only` 仅构建兼容测试样例，不能作为兼容通过证据。发布脚本只生成本地附件，不提交、推送或创建 GitHub Release。

生成发布附件的 Go SBOM 需要 Go 1.25+；仅构建业务启动器仍支持 Go 1.24+。Windows 原生文件选择框的 PowerShell 桥接属于运行时系统适配，不要求工具用户安装 Python。

## 3. CLI

所有子命令支持 `--help`。密码只能通过受控输入源提供：`--password-file <UTF-8 文件>`、明确选择的 `--password-stdin`（读取一行且最多
64 KiB），或终端的隐藏交互输入。没有明文 `--password` 参数；工具默认不读取 stdin，以免抢占业务输入。密码文件保留空格和字符原样，只剥离一个末尾换行，不会
trim 或做 Unicode 归一化。不要把密码放进 shell 历史、CI 日志或普通配置导出。

可执行工具的标准输出和错误输出统一为 UTF-8，脚本采集 JSON 时也应按 UTF-8 解码。Java API 不修改调用方的全局输出流；其日志和编码由宿主程序决定。

Windows 上部分 JDK 的 `java.exe` 会先用系统代码页转换启动参数；英文系统可能在进入 Java 代码前把中文或 emoji 变为 `?`（见 [OpenJDK 21 启动器实现](https://github.com/openjdk/jdk21u/blob/jdk-21.0.12%2B8/src/java.base/share/native/launcher/main.c)）。`-Dfile.encoding=UTF-8` 或修改终端显示编码不能修复这一步。构建时优先用 Web UI / Java API 处理非 ASCII 路径，密码使用 UTF-8 文件；目标应用的复杂文本优先放入其支持的 UTF-8 配置文件，而不是启动参数。Go 启动器最终也调用目标 Java，不绕过该 JDK 限制。请在实际目标系统验证所需字符，不要仅以终端显示正常判断传参正确。

### 3.1 独立加密、验证、查看和恢复

以下命令不依赖特定 shell；路径带空格时用双引号包围完整参数。

```text
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar encrypt --input app.jar --output app-encrypted.jar --password-file private/password.txt
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar inspect --input app-encrypted.jar
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar verify --input app-encrypted.jar --password-file private/password.txt
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar decrypt --input app-encrypted.jar --output app-restored.jar --password-file private/password.txt
```

`inspect`检查归档结构并输出 JSON，不验证加密内容；`verify`需密码并认证受保护内容；`decrypt`
恢复原始归档的全部条目内容，写到新的输出路径，不接受筛选或依赖加密选项。恢复不承诺 ZIP 容器字节级一致，重新封装后的整个文件
SHA-256 可能不同。`encrypt` 和 `package` 可重复传入筛选规则 `--include <glob>` 和 `--exclude <glob>`；默认依赖原样保留，只有显式传入
`--encrypt-dependencies` 才将嵌套依赖纳入加密范围。

对加密归档仅执行 `inspect` 时，原始主类和最低 Java 版本可能未知；`minimumJava` 返回 `null`，不能据此按 Java 8 部署。提供密码执行
`verify` 后，工具从已认证内容推导最低版本。Java API 的 `ArchiveInfo.minimumJava()` 相应返回可空的 `Integer`，调用方应先判断是否已知。

外层输入 JAR 含签名记录时会直接拒绝，因为替换启动 manifest
和注入运行时必然修改归档。签名嵌套依赖默认原样保留；只有显式开启依赖加密且筛选命中其内容、需要重写该依赖时才拒绝。工具不会静默删除签名，也不会把修改后的内容宣称为仍有原签名。

`doctor`用于检查当前构建环境：

```text
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar doctor
```

Go 检查分别报告 `go.available`（是否找到程序）、`go.version`、`go.minimum`、`go.compatible` 和 `go.reason`。打包要求 Go
1.24+；找到旧版本或版本探测失败时不会误报为可打包。探测最多等待约 3 秒并限制输出大小，不自动下载工具链。没有 Go 不影响独立加密
JAR，`doctor` 仍会完成其他检查；脚本应读取兼容状态，不能只看命令退出码。

### 3.2 构建 Go 启动器

先创建一次发行凭据，并将其存放在只有发布者可读的私有位置：

```text
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar license init --app-id demo --output private/demo.issuer
```

对同一应用的后续构建和续期始终复用相同的 `.issuer` 与完全一致的 `appId`。`appId` 是 1..256 字节的严格 UTF-8
标识，按字节精确匹配，不做大小写折叠或 Unicode 归一化。凭据是签发秘密，不发给客户、不提交 Git、不纳入 SDK、Skill ZIP
或其他公共产物。不要用 `appId` 拼接安装目录；使用命令结果中的实际输出路径。

示例为 Windows amd64 目标，省略 `--jdk-zip` 时目标机必须有适用的 Java：

```text
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar package --input app.jar --output dist --app-id demo --issuer-file private/demo.issuer --target windows-amd64 --password-file private/password.txt
```

有效目标为 `windows-amd64`、`windows-arm64`、`linux-amd64`、`linux-arm64`、`darwin-amd64`、`darwin-arm64`。需要随包携带目标
JDK 时传入 `--jdk-zip <目标平台 JDK 压缩包>`；JDK 的 OS/架构必须与 `--target` 一致。Go 编译器可通过 `--go-executable <路径>`
指定，否则从 PATH 查找。JVM 参数和业务参数用重复的 `--jvm-arg <值>`、`--app-arg <值>` 传入，参数按数组传递，不经过 shell 拼接。

macOS 的 Apple Silicon 选择 `darwin-arm64`，Intel 选择 `darwin-amd64`；其他参数和操作流程相同。维护脚本要求 JDK 根目录时，macOS 的 `.jdk` 应用包通常应使用其 `Contents/Home` 子目录。

许可证生效时间 `--not-before` 和到期时间 `--not-after` 使用 ISO-8601 instant，例如 `2027-12-31T16:00:00Z`
。省略到期时间表示永久有效；如果需要有期限授权，建议明确给出两个边界并核对时区。`notBefore` 为含边界，`notAfter` 为不含边界。

构建结果会报告启动器绝对路径；启动器与 `key.x` 位于同一应用输出目录。交付部署时只提供该启动器和对应 `key.x`，不要提供
`.issuer`、构建临时目录、应用源包之外的秘密或客户数据。实际交付前在目标系统验证真实运行，而不是只以交叉编译成功作为证据。

### 3.3 签发与续期

从已有 `.issuer` 为同一 `appId` 签发一个新 `key.x`，并将新旧文件分别保存：

```text
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar license issue --issuer-file private/demo.issuer --output renewal/key.x --not-before 2027-01-01T00:00:00Z --not-after 2028-01-01T00:00:00Z
```

部署端先备份当前 `key.x`，再将新文件替换到启动器旁。已运行的启动器只使用启动时加载的许可证；替换文件后需要重启启动器，续期才生效。错误、缺失、不匹配、未生效或已过期的许可证不会放行业务应用；本机状态服务仍可用于诊断。

## 4. Java API：在测试类 `main()` 中操作

将 `xjar-plus-core` 加入 Maven 项目（其余核心模块由 Maven 依赖带入）。当前仓库不自动发布 Maven Central；从源码 `mvn install`
，或在 SDK 解压目录执行 `python install_sdk.py`（macOS/Linux 通常用 `python3 install_sdk.py`），安装库、POM 和源码 JAR 到本机 Maven 仓库。依赖坐标：

```xml
<dependency>
  <groupId>io.github.kongweiguang.xjar.plus</groupId>
  <artifactId>xjar-plus-core</artifactId>
  <version>2.0.0</version>
</dependency>
```

在 `src/test/java` 创建一个可直接从 IDE 运行的 `main()`，用密码文件而不是源码常量存放密码：

```java
import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import io.github.kongweiguang.xjar.plus.format.Passwords;

import java.nio.file.Path;
import java.util.Arrays;

public final class EncryptMain {
    /** 仅保留无状态的测试入口，避免将示例当作可复用服务。 */
    private EncryptMain() { }

    /** 从私有文件读取口令，并在结束后清理当前方法持有的口令缓冲区。 */
    public static void main(String[] args) throws Exception {
        final char[] password = Passwords.readFile(Path.of("private/password.txt"));
        try {
            try (EncryptionRequest request = EncryptionRequest.builder()
                    .input(Path.of("target/app.jar"))
                    .output(Path.of("target/app-encrypted.jar"))
                    .password(password)
                    .progress((stage, percent) -> System.out.printf("%3d%% %s%n", percent, stage))
                    .build()) {
                final var result = XJarPlus.encrypt(request);
                XJarPlus.verify(result.output(), password);
                System.out.println(result.output());
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
```

这只是独立加密用例。启动器 API、发行凭据创建和续期的完整 `main()` 示例见 [`examples/`](../examples/README.md)、[
`PackageMain.java`](../examples/PackageMain.java)、[`IssuerMain.java`](../examples/IssuerMain.java)、[
`LicenseMain.java`](../examples/LicenseMain.java)。不要把示例路径、密码或发行凭据复制为生产默认值。

## 5. 本地 Web UI

只有显式执行 `ui` 才会启动 UI。默认监听本机回环的系统分配端口，并在桌面环境可用时打开浏览器；`--port 0..65535` 可选择监听端口，
`--no-browser` 只打印地址：

```text
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar ui
java -jar xjar-plus-app/target/xjar-plus-2.0.0.jar ui --port 19876 --no-browser
```

页面按所选产物模式显示必填字段；筛选规则、依赖加密、JDK、授权时间和启动参数放在默认折叠的高级设置中。导入含高级设置的配置会展开对应分组，切换模式不清空已经填写的值。配置导出排除密码、发行凭据路径和可能夹带秘密的
JVM/应用参数；输入、输出等本机路径仍保留，分享前应检查。

进度来自后台处理阶段，成功、失败和取消分别显示为明确终态。终态后的“重试”使用当前表单中的参数，需要重新输入密码；修改输出路径后不会再提交旧路径。提交前只把随机任务
ID 写入当前标签页的 `sessionStorage`，不保存请求体或秘密。提交响应丢失、短暂断线或刷新页面时按 ID
查询原任务，不自动重新提交构建；服务仍保留该任务时，同 ID 的重复提交只对应一次执行。

任务记录仅在本次 UI 服务内有界保留，最多 128 条，终态记录按 30
分钟保留策略及容量回收；这不是持久任务系统。服务重启或记录回收后，页面会提示结果无法确认，须先检查输出位置再显式新建。浏览器不支持会话存储时会在提交前提示，不会承诺刷新恢复；可改用
CLI。刷新不会恢复密码或整份表单，需新建任务时应重新填写或导入非敏感配置。

文件选择只在用户点击选择按钮后请求系统文件选择器；等待期间可点击“取消选择”关闭该次选择流程，取消不会清空原路径，也不会启动加密。如果系统选择器不可用，可手动粘贴路径。Windows
优先使用 PowerShell 7，兼容失败才回退 5.1，并在服务终端记录原因；诊断阶段标记不包含选择路径或密码。

Windows helper 使用当前用户专属权限的临时目录传递固定脚本，避免长命令行限制；结束、取消或 300 秒超时后清理。详细选择器诊断默认关闭，需要排查时可运行
`java "-Dxjar.plus.debug-picker=true" -jar xjar-plus-app/target/xjar-plus-2.0.0.jar ui`，日志只输出白名单内的阶段和自身窗口状态，不记录所选路径或密码。

UI 服务只绑定 `127.0.0.1`，用会话令牌、Host/Origin 校验和同源策略保护本地
API，不提供跨域访问。后台任务有并发和队列上限，任务详情有保留时间上限。密码不会写入页面本地存储、任务响应、配置导出或日志；任务结束时清零可变密码副本并释放引用。浏览器和
JSON 解析产生的不可变字符串不能保证从进程堆内存中物理擦除，仍应避免在共享设备上操作敏感应用和凭据。系统文件选择器的跨平台视觉与焦点行为尚未完成人工验收。

### 使用 Skill 辅助操作

仓库已提供 `.agents/skills/xjar-plus/SKILL.md`。下载 Skill ZIP 后，将其中的 `xjar-plus` 文件夹放到使用项目的
`.agents/skills/`，或用户目录下的 `.agents/skills/`，即可按 Codex
的[官方本地技能发现规则](https://learn.chatgpt.com/docs/build-skills#where-codex-loads-local-skills)使用。无需修改全局设置或安装额外插件。

示例请求：`使用 $xjar-plus，检查我的 Java 环境，把指定原始 JAR 加密到一个新文件并验证启动。` Skill 会帮助选择独立 JAR 或 Go
授权模式、核对环境和执行验证；它不会自动发布产物，也不应要求你在聊天里粘贴密码或发行密钥。

## 6. 运行加密后的应用

独立加密 JAR 需要密码源，但不读取应用的 stdin。可在有终端的环境交互输入隐藏密码，或明确指定私有密码文件：

```text
java "-Dxjar.plus.password-file=private/app-password.txt" -jar app-encrypted.jar
```

普通 JAR 为兼容 JDK 创建的不继承上下文加载器的线程，使用系统类加载器桥接。上述简便启动方式会先启动一个轻量入口进程，再以相同
Java 启动业务 JVM；入口继承标准流、等待业务结束并转交退出码。希望直接使用单个 JVM 时，在 `-jar` 前显式加入：

```text
-Djava.system.class.loader=io.github.kongweiguang.xjar.plus.runtime.common.XJarPlusSystemClassLoader
```

不要同时指定其他系统类加载器。Java agent、调试端口、JFR 等有启动副作用的选项不能假定可安全重复启动；简便启动路径会拒绝已知不安全选项，提示改用显式桥接并自行验证所需工具。JVM
可能打印自定义系统类加载器影响 CDS 的警告；这是 JVM 提示，不代表认证失败。Go 打包普通 JAR 时会自动加入桥接参数、直接启动单个业务
JVM。Spring Boot 和 Solon 使用自身适配器，不需要此参数。

Windows 普通 JAR 自动重启前会检查当前 JVM 报告的本机字符集；参数无法无损表示或字符集不可识别时明确拒绝，不让第二次启动静默替换字符。错误提示不回显参数内容。此检查不能恢复首次 `java.exe` 启动时已经丢失的字符，也不支持用 `-Dsun.jnu.encoding` 伪造本机编码；请改用可表示的路径/参数或在匹配字符集的运行环境重试。

Go 启动器模式由启动器监督应用、提供许可证状态，不需要目标应用 stdin 作为供钥通道；业务 stdin/stdout/stderr
按进程参数继承。许可证状态接口只监听本机，默认端口 `19527`，可通过非秘密环境变量 `XJAR_PLUS_STATUS_PORT` 指定 `0`（系统分配）或
`1..65535`；地址绑定失败会导致启动失败，而不是假报就绪。

- `GET`/`HEAD http://127.0.0.1:<port>/xjp/healthz`：进程状态服务健康；GET 正文为 `ok`。
- `GET`/`HEAD http://127.0.0.1:<port>/xjp/license/status`：许可证快照。`valid` 表示授权在当前时间有效；`appRunning`
  表示业务进程是否运行，两者是不同维度。响应也包含可用性、有效时间、剩余时长、服务端时间和可选的非敏感说明。

接口不接受写请求。监听只在 IPv4 loopback 上，不应将其当作远程管理接口；端口设置不会改变绑定主机。

### 6.1 状态接口接入契约

适用版本为 2.0.0；契约来源是 `launcher/internal/status/status.go`，对应测试为 `status_test.go`
。这两个只读接口不要求令牌，但仅供同机调用，不设置跨域授权，也不应通过反向代理公开。状态服务没有修改许可证的接口；续期仍按 3.3
节替换文件并重启。

`GET /xjp/license/status` 无请求参数，返回 HTTP 200 和 `application/json; charset=utf-8`，即使许可证无效也返回 200。响应含
`Cache-Control: no-store`；调用方必须判断正文，而不能把 HTTP 200 当作业务健康或授权成功。

| 字段             | 类型、存在性   | 业务含义                                                                         |
|------------------|----------------|----------------------------------------------------------------------------------|
| `available`      | boolean，必有  | 启动时成功读取、认证并匹配本应用的许可证；过期但可认证的许可证仍为 true。        |
| `valid`          | boolean，必有  | 按当前服务端时间检查授权是否已生效且未到期。                                     |
| `appRunning`     | boolean，必有  | 受管业务进程仍在运行；退出确认后才为 false，不代表业务自己的 HTTP 健康检查通过。 |
| `validStartDate` | string，必有   | UTC RFC 3339 生效时间，含边界；许可证不可用时为空字符串。                        |
| `validEndDate`   | string，必有   | UTC RFC 3339 到期时间，不含边界；永久或许可证不可用时为空字符串。                |
| `perpetual`      | boolean，必有  | 已认证的永久授权；仍需同时检查 `valid`，因为可能尚未生效。                       |
| `remainingMs`    | integer，必有  | 有限且有效授权的剩余毫秒数；不可用、未生效、已过期或永久时为 0。                 |
| `remaining`      | string，可省略 | 有限且有效授权的展示文本，例如 `01h 00m 00s`；不要解析此文本做授权判断。         |
| `serverTime`     | string，必有   | 本次查询的 UTC RFC 3339 时间；状态依赖本机时钟，不能抵御回拨。                   |
| `message`        | string，可省略 | 非敏感的人类可读说明；不是稳定错误码。                                           |

下面是合成示例，不是某台客户机器的实测结果。有效授权且业务进程运行：

```json
{"available":true,"valid":true,"validStartDate":"2027-01-01T00:00:00Z","validEndDate":"2027-01-01T01:00:00Z","perpetual":false,"remainingMs":3600000,"remaining":"01h 00m 00s","serverTime":"2027-01-01T00:00:00Z","appRunning":true}
```

缺失、损坏或不匹配的许可证使用统一的非敏感失败说明：

```json
{"available":false,"valid":false,"validStartDate":"","validEndDate":"","perpetual":false,"remainingMs":0,"serverTime":"2027-01-01T00:00:00Z","appRunning":false,"message":"license file is unavailable"}
```

`GET /xjp/healthz` 返回 HTTP 200、`text/plain; charset=utf-8` 和 `ok\n`，只证明状态服务可访问。两条路径的 HEAD
返回对应状态和头、不返回正文；其他方法返回 405 和 `Allow: GET, HEAD`，未定义的路径返回 404。连接失败不是授权状态，应先检查启动器日志、进程和端口绑定。

最小联调顺序：启动分发件，先访问 healthz，再读取 status；只有 `valid=true` 且 `appRunning=true` 才进入业务自己的就绪检查。到期停止期间可能短暂出现
`valid=false`、`appRunning=true`，不得继续视为授权有效；确认进程结束后才会变成 false。换证后重启并重复这三个步骤。查询可安全重试，建议设置
3 秒客户端超时并间隔重试，禁止忙循环；这里没有分页、写入重试或幂等提交语义。

## 7. 安全边界

- 算法仅有 AES-256-GCM；密码封装和 Java/Go 共用二进制格式见 [`format-v2.md`](format-v2.md)
  。更改密文、索引、路径、分块或认证标签应失败，不存在认证失败后回退明文的模式。
- 加密只提高静态分发的读取门槛；应用运行时仍必须向 JVM 提供可执行的类和资源。不能据此承诺客户端无法提取明文、密钥或修改程序。
- Go 许可证依赖客户端可控的系统时钟和客户端程序。它适合本地授权流程，不是抵御管理员篡改、逆向或时钟回拨的强 DRM。
- 独立加密 JAR 不执行许可证或到期限制。需要授权监督时选 Go 启动器模式。
- `.issuer` 是发布者秘密，`key.x` 是运行期许可证；二者职责不同。不要把 `.issuer` 交付给客户。

## 8. 从 V1 迁移

2.0.0 是破坏性大版本：包名和 Maven 坐标调整，密文、密钥信封、发行凭据和 `key.x` 均采用新格式；旧 API、旧密文和旧许可证不能直接复用。

1. 旧版工具和旧产物继续留在原有部署中，直到迁移完成。
2. 对需要迁移的应用，使用对应旧版工具从旧分发件恢复原始 JAR。
3. 验证恢复出的 JAR 能以原方式正常运行，并安全保存原件。
4. 用 2.0.0 从原始 JAR 重新加密/打包，重新创建新的发行凭据，并在目标机完成验证。
5. 按既有发布与回滚流程切换产物；2.0 不提供自动转换旧密文或旧 `key.x` 的工具。

## 9. 兼容验证与发布状态

固定版本矩阵见 [`compatibility-matrix.json`](compatibility-matrix.json)。2026-10-23 在 Windows 11 amd64 完成 24 项测试：普通
JAR 五个 JDK、Boot 16 个 minor 的固定补丁、Boot 3.2 CLASSIC 额外布局和 Solon
两个版本，零失败、零跳过。验证包括实际业务启动、资源/SPI、HTTP、错误密码和解密恢复；Boot 4.1 还覆盖选中嵌套 Multi-Release
条目的密文与运行行为。

2026-10-24 的 [Linux 发布前兼容测试](https://github.com/kongweiguang/xjar-plus/actions/runs/39988335391) 在提交 `10dd1a8` 上也通过全部 24 组。JSON 的 `verification` 保留 Windows 本地候选及其哈希，`ciVerification` 单独记录这次 Linux 结果；两者都不是后续版本或最终 tag 的替代证据。

**保留的框架原生限制：** Boot 2.1.18.RELEASE、2.2.13.RELEASE、2.3.12.RELEASE 的 multipart Range 响应把原资源长度写入
`Content-Length`。16/20 字节资源的 MIME 正文因此只留下边界前缀；131089 字节资源则在完整双段 MIME 结束后关闭连接，正文远小于声明长度，严格
HTTP 客户端会拒绝。测试分别验证原包、保护包和恢复包的状态、声明长度、精确截断前缀，以及大资源的两个完整分段与结束边界，不把
HTTP 206 当作完整可用。普通 GET、HEAD、单段 Range 和其他资源契约仍完整验证；XJar Plus 不修改原框架的这一行为。

Windows Go 启动器已实测内嵌 JDK、无效授权、到期后约 5 秒强停兜底、状态接口继续服务、换证重启、参数/stdin 与临时目录清理。WSL
Linux 已实测加密和真实 Go 编译中的 SIGTERM 取消。macOS/ARM 实机、系统选择框的高 DPI/焦点交互仍未验收；交叉编译成功不等于这些目标已实际运行。

本地模块的 Checkstyle、PMD、SpotBugs 和分层依赖检查已通过，这些检查只是阿里巴巴规范的可自动化子集，不构成全部规范认证。Release workflow 对最终 tag 执行质量、兼容性和启动器平台门槛，通过后生成附件，并在公开前下载回读、核对 SHA-256 和 CLI 版本。

`scripts/release.py` 只在本地生成 CLI/UI JAR、SDK ZIP、Skill ZIP、兼容矩阵、SBOM 与校验清单，不自行提交、推送或发布。正式附件见 [2.0.0 Release](https://github.com/kongweiguang/xjar-plus/releases/tag/v2.0.0)，最终 tag 的验收结果以 [Release workflow](https://github.com/kongweiguang/xjar-plus/actions/workflows/release.yml) 对应运行记录为准；没有自动发布 Maven Central。
