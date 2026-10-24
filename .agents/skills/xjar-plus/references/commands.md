<!-- @author kongweiguang -->

# CLI 完整使用手册（2.0.0）

本页可直接作为用户手册阅读，不要求安装 AI skill。命令中的 `app.jar`、包名、目录和日期都是示例，请换成自己的值。示例假设当前目录里有下载的 `xjar-plus-2.0.0.jar`；源码构建的工具在 `xjar-plus-app/target/`。标为 `text` 的单行命令适用于 Windows PowerShell、macOS/Linux 终端。

## 目录

- [1. 环境、帮助与密码输入](#environment)
- [2. 指定包名、类和资源的加密范围](#selection)
- [3. 独立 JAR：加密、检查、验证、恢复](#standalone)
- [4. 启动加密 JAR，传入 JVM 和业务参数](#run-jar)
- [5. Go 启动器：初始化、打包、部署](#package)
- [6. 许可证签发、续期和应用升级](#license)
- [7. 启动器运行、端口和状态接口](#launcher)
- [8. 全部命令、参数、输出与退出码](#reference)
- [9. 常见问题和重试](#troubleshooting)

Java 代码入口见 [Java API 手册](java-api.md)，网页操作见 [Web UI 手册](web-ui.md)。

<a id="environment"></a>
## 1. 环境、帮助与密码输入

### 1.1 需要安装什么

| 操作 | 需要的环境 |
|---|---|
| 运行工具 CLI / Web UI / Java API | Java 21+；源码构建另需 Maven |
| 构建独立加密 JAR | 不需要 Go、许可证、JDK ZIP |
| 构建 Go 授权启动器 | 构建机安装 Go 1.24+；依赖缓存不齐时需要访问 Go module 源 |
| 启动独立加密 JAR | 满足原应用及其适配器要求的 Java，并支持 AES-256-GCM |
| 启动 Go 分发件 | 目标机不需要 Go；未内嵌 JDK 时需要兼容的 Java |

工具的 Java 21 要求不代表目标应用也必须使用 Java 21。普通可执行/uber JAR 必须有 `Main-Class`；支持标准 Spring Boot 2.x/3.x/4.x 和 Solon 4.x 可执行布局。WAR、Native Image、模块路径启动及任意自定义 Launcher 不在范围内。

```text
java -version
java -jar xjar-plus-2.0.0.jar --version
java -jar xjar-plus-2.0.0.jar --help
java -jar xjar-plus-2.0.0.jar doctor
java -jar xjar-plus-2.0.0.jar encrypt --help
java -jar xjar-plus-2.0.0.jar package --help
java -jar xjar-plus-2.0.0.jar license issue --help
```

无参数只显示帮助。`doctor` 无额外业务参数，检查 Java、AES、Go 和可写环境；Go 缺失不影响独立 JAR。它分别输出 `go.available`、`go.version`、`go.minimum`、`go.compatible`、`go.reason`，不能仅凭退出码判断 Go 打包条件；不会代替你安装 Go。

### 1.2 三种工具端密码来源

| 方式 | 写法 | 适合场景 |
|---|---|---|
| 隐藏交互输入 | 不传密码选项，按终端提示输入 | 手动执行，有真实控制台 |
| UTF-8 密码文件 | `--password-file "private/password.txt"` | IDE、CI、脚本；推荐 |
| 标准输入一行 | `--password-stdin` | 明确由管道或自动化进程供给 |

`--password-file` 与 `--password-stdin` 不能同时传。没有控制台时必须显式选择一种来源；工具没有 `--password 明文` 选项。

用编辑器创建私有 UTF-8 文件，例如 `private/password.txt`，内容就是密码，限制文件权限为当前用户可读。上限 64 KiB；只去掉一个末尾 LF 或 CRLF，其他空格和 Unicode 字符保留原样，不做 trim 或 Unicode 归一化。避免编辑器意外添加 BOM、空行或尾随空格。密码文件与应用分开保管，不提交 Git，不放入客户分发包。

macOS/Linux 可把已有密码文件重定向给工具，不把密码值写进命令历史：

```sh
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-encrypted.jar --password-stdin < private/password.txt
```

PowerShell 不使用上述 `<` 重定向，直接用通用的 `--password-file`。自动化采用 `--password-stdin` 时只写入一行 UTF-8；工具读取第一行，不把后续内容作为业务输入转交。**这是工具命令的选项；加密后的业务 JAR 不提供 `--password-stdin`，也不会读取业务 stdin 来取密码。**

路径带空格时给完整值加引号，例如 `--input "my app/app.jar"`。所有 glob 都加引号，避免终端先展开 `*`。相对路径以当前工作目录为基准。

<a id="selection"></a>
## 2. 指定包名、类和资源的加密范围

### 2.1 先看自己 JAR 的实际结构

**筛选的是 JAR 内部路径，不是 Java 点号包名。** `com.example.service` 要换成 `com/example/service/`。普通 JAR 与 Spring Boot 的路径不同；工具不会自动加前缀。

安装了 JDK 时列出路径：

```text
jar tf app.jar
```

也可用压缩软件只读打开 JAR。`inspect` 只返回布局概要，不列出全部文件或逐文件加密清单。

| 实际内容 | JAR 内路径示例 | 保护整个包及其子包的规则 |
|---|---|---|
| 普通 JAR / uber JAR 的应用类 | `com/example/order/OrderService.class` | `com/example/order/**` |
| Spring Boot 的应用类 | `BOOT-INF/classes/com/example/order/OrderService.class` | `BOOT-INF/classes/com/example/order/**` |
| Solon 标准嵌套布局的应用类 | `BOOT-INF/classes/com/example/order/OrderService.class` | `BOOT-INF/classes/com/example/order/**`，以实际条目为准 |
| Boot 的业务依赖类 | `BOOT-INF/lib/business.jar!/com/example/order/OrderService.class` | `BOOT-INF/lib/business.jar!/com/example/order/**`，并开启依赖加密 |
| Solon 的业务依赖类 | `BOOT-INF/lib/business.jar!/com/example/order/OrderService.class` | `BOOT-INF/lib/business.jar!/com/example/order/**`，并开启依赖加密 |

`!/` 是“嵌套 JAR 内部”的逻辑分隔符。`jar tf app.jar` 直接看到的是外层依赖 JAR 名；依赖内部的包路径要打开该依赖再看。规则里的依赖版本文件名必须与实际一致。

若 Solon 输入确实把应用类放在根包目录，就使用对应根路径；依赖也可能在 `lib/` 或 `libs/`。以 `jar tf` 结果为准，不根据框架名字猜前缀。这里的 Boot/Solon 标准路径已用本仓库真实样例验证。

### 2.2 规则怎样组合

- 没有 `--include`：默认选中应用自身可保护的 class 和资源。嵌套依赖仍默认不改。
- 有一个或多个 `--include`：只选中至少命中一条 include 的内容，多个 include 是“或”。增加规则就重复选项，不用逗号连接。
- `--exclude` 最后生效：命中任一 exclude 就保留明文，优先级高于 include。
- 不选中/排除的文件仍在产物里，**不是删文件**。明文条目仍受认证索引的完整性校验保护。
- 启动 manifest、框架 loader 及必要引导元数据按运行约束单独处理；写 `**` 也不等于 ZIP 每个字节都变成密文。
- include/exclude 只用于 `encrypt`、`package`。`verify` 验证全部受保护内容，`decrypt` 完整恢复，不接受筛选选项。

规则是 Java 文件系统 `glob`，不是正则表达式或旧版 XJar 包名表达式。用 `/` 分隔路径，不添加前导 `/`、`classpath:` 或 `glob:`。按条目真实大小写填写，不依赖不同宿主文件系统的大小写差异。

| 语法 | 含义与例子 |
|---|---|
| `*` | 当前路径段内零个或多个字符，不跨 `/`；`Order*.class` 可匹配 `OrderService.class` |
| `**` | 可跨目录；`com/example/**` 包含该包、所有子包、类及包内资源 |
| `?` | 当前路径段内一个字符；`config?.yml` |
| `[abc]` / `[a-z]` / `[!a-z]` | 单字符集合、范围或否定集合，按宿主 Java glob 规则解释 |
| `{a,b}` | 备选分组，例如 `**/*.{yml,yaml}`；这里的逗号属于 glob，不是 CLI 列表分隔符 |

`**/*.class` 需要路径里有 `/`，不能代替根目录 `Main.class` 的 `*.class`；`**/com/example/**` 也不保证命中从根开始的 `com/example/**`。要兼顾根目录和 Boot 布局，明确重复两条 include。Multi-Release 覆盖类位于 `META-INF/versions/<版本>/...`，选根包不会自动选中这些版本目录，按实际路径另加规则。

### 2.3 可直接改包名使用的例子

**普通 JAR，只保护 `com.example.order`：**

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-order.jar --password-file private/password.txt --include "com/example/order/**"
```

**Spring Boot / 标准嵌套布局 Solon，只保护同一个包：**

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-order.jar --password-file private/password.txt --include "BOOT-INF/classes/com/example/order/**"
```

**两个业务包，排除公开 DTO：**

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-selected.jar --password-file private/password.txt --include "BOOT-INF/classes/com/example/order/**" --include "BOOT-INF/classes/com/example/payment/**" --exclude "BOOT-INF/classes/com/example/order/dto/**" --exclude "BOOT-INF/classes/com/example/payment/dto/**"
```

**默认加密应用内容，把配置和静态资源留明文（Boot）：**

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-no-public-resources.jar --password-file private/password.txt --exclude "BOOT-INF/classes/application*.yml" --exclude "BOOT-INF/classes/application*.yaml" --exclude "BOOT-INF/classes/application*.properties" --exclude "BOOT-INF/classes/static/**"
```

**某个类及其内部类，另加 Mapper XML（Boot）：**

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-class-and-mapper.jar --password-file private/password.txt --include "BOOT-INF/classes/com/example/order/OrderService*.class" --include "BOOT-INF/classes/mapper/order/**"
```

`OrderService*.class` 也会匹配同前缀其他类，例如 `OrderServiceHelper.class`；精确范围需列出具体文件。只 include 包目录不等于只加密 `.class`，包内资源也会选中。

**兼顾普通 / Boot 路径，并保护 Multi-Release 覆盖类：**

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-multi-layout.jar --password-file private/password.txt --include "com/example/**" --include "BOOT-INF/classes/com/example/**" --include "META-INF/versions/*/com/example/**" --include "BOOT-INF/classes/META-INF/versions/*/com/example/**"
```

这是合并候选路径，不保证自定义布局也受支持。include 没命中业务文件时命令仍可能成功，因为还有必须保护的元数据；`verify` 通过也不代表包名规则命中了目标类。先核对路径，并检查所选类已不再是可直接读取的原始 class。

### 2.4 同时保护指定业务依赖

第三方依赖默认原样保留。下面保护应用包及自己编写的 `business-1.0.jar` 中的 `com.example.secret`：

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-with-business-lib.jar --password-file private/password.txt --encrypt-dependencies --include "BOOT-INF/classes/com/example/**" --include "BOOT-INF/lib/business-1.0.jar!/com/example/secret/**"
```

必须同时满足 **依赖开关开启 + 完整嵌套路径命中 include 且未命中 exclude**。只写 `--include "BOOT-INF/lib/business-1.0.jar"` 不会选中内部 class。多个依赖重复 include；按版本名匹配可写 `BOOT-INF/lib/business-*.jar!/com/example/secret/**`。

开依赖加密却不写 include，会把可处理的依赖内容也纳入默认范围。外层签名输入 JAR 会被拒绝；有签名的依赖在需要重写时也被拒绝，不静默删除签名。普通 uber JAR 已把依赖类平铺，工具不能按 Maven 来源区分归属，需用包路径筛选；依赖开关不是“按 Maven 坐标筛选”。

`package` 使用完全相同的筛选参数，例如：

```text
java -jar xjar-plus-2.0.0.jar package --input app.jar --output dist --password-file private/password.txt --app-id demo --issuer-file private/demo.issuer --target darwin-arm64 --include "BOOT-INF/classes/com/example/order/**" --exclude "BOOT-INF/classes/com/example/order/dto/**"
```

<a id="standalone"></a>
## 3. 独立 JAR：加密、检查、验证、恢复

先确认原包能使用它要求的 Java 正常启动，准备不存在的新输出：

```text
java -jar xjar-plus-2.0.0.jar inspect --input app.jar
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-encrypted.jar --password-file private/password.txt
java -jar xjar-plus-2.0.0.jar inspect --input app-encrypted.jar
java -jar xjar-plus-2.0.0.jar verify --input app-encrypted.jar --password-file private/password.txt
java "-Dxjar.plus.password-file=private/password.txt" -jar app-encrypted.jar
```

`encrypt` 已验证暂存产物，成功后才提交输出；单独 `verify` 用于复制/下载后复核。`inspect` 不读密码，只检查结构；加密 JAR 的 `mainClass` / `minimumJava` 可能为 `null`，附 `minimumJavaWarning`。`verify` 才返回认证后的应用入口及 Java 下限。

恢复明文需写到另一个新文件：

```text
java -jar xjar-plus-2.0.0.jar decrypt --input app-encrypted.jar --output app-restored.jar --password-file private/password.txt
java -jar app-restored.jar
```

恢复原始条目内容及 manifest，不承诺 ZIP 压缩元数据逐字节一致，整文件 SHA-256 可能不同。原文件、加密文件和恢复文件分别保存。没有覆盖开关、原地加密、旧密文升级或修改原密文密码的命令；换密码或调整范围，从原始 JAR（或先合法恢复出的 JAR）重新构建。

<a id="run-jar"></a>
## 4. 启动加密 JAR，传入 JVM 和业务参数

真正的控制台可隐藏交互输入密码；服务、IDE 或重定向环境使用私有密码文件：

```text
java -jar app-encrypted.jar
java "-Dxjar.plus.password-file=private/password.txt" -jar app-encrypted.jar
java -Xmx512m "-Dxjar.plus.password-file=private/password.txt" -jar app-encrypted.jar --server.port=8080 "--label=含 空格"
```

JVM 的 `-Xmx`、`-D...` 在 `-jar` 前，业务参数在 JAR 路径后。密码文件属性整体加引号，兼容 PowerShell 和空格路径。启动业务不需要工具 JAR、Go、许可证或到期日期。

普通 JAR 的简便入口会用相同 Java / 参数重启一次业务 JVM，提供系统类加载器桥接，并转交标准流和退出码。要直接使用单 JVM，或使用 Java agent、调试端口、JFR 等有副作用的 JVM 选项时，显式设置桥接并自行验证工具兼容性：

```text
java "-Djava.system.class.loader=io.github.kongweiguang.xjar.plus.runtime.common.XJarPlusSystemClassLoader" "-Dxjar.plus.password-file=private/password.txt" -jar app-encrypted.jar
```

不要同时设置另一系统类加载器。简便重启拒绝已知不安全的重复 JVM 选项。日志可能出现 CDS 提示，这是 JDK 对自定义加载器的提示。Boot / Solon 使用专属适配器，不额外套用普通 JAR 桥接；Go 打包普通 JAR 自动使用直接桥接方式。

业务 stdin 保留给原应用；重定向业务输入时，密码仍用单独文件。Windows 部分 Java 按系统代码页转换原生 argv，英文系统的中文/emoji 可能在进入 Java 前损坏，`file.encoding` 无法修复。使用可表示的路径/参数，或用 Web UI / Java API 构建，并在目标环境验证。

<a id="package"></a>
## 5. Go 启动器：初始化、打包、部署

需要许可证、续期、到期停止和本机状态接口时用此模式。构建机先安装 Java 21 / Go 1.24+，`doctor` 检查 PATH 中的默认 Go。

### 5.1 第一次为应用创建发行凭据

```text
java -jar xjar-plus-2.0.0.jar license init --app-id demo --output private/demo.issuer
```

同一应用只创建一次。`.issuer` 是发布者签发秘密，需安全备份；不能通过同名 `appId` 重新初始化替代丢失的凭据。`appId` 为 1..256 字节严格 UTF-8，区分大小写，不做 Unicode 归一化；打包时必须与凭据标识完全一致。它不是文件路径。

### 5.2 使用目标机器的 Java

以下为三次独立构建，按目标系统选择对应平台和新输出位置：

```text
java -jar xjar-plus-2.0.0.jar package --input app.jar --output dist/windows --password-file private/password.txt --app-id demo --issuer-file private/demo.issuer --target windows-amd64
java -jar xjar-plus-2.0.0.jar package --input app.jar --output dist/macos --password-file private/password.txt --app-id demo --issuer-file private/demo.issuer --target darwin-arm64
java -jar xjar-plus-2.0.0.jar package --input app.jar --output dist/linux --password-file private/password.txt --app-id demo --issuer-file private/demo.issuer --target linux-amd64
```

| 目标机器 | `--target` |
|---|---|
| Windows x64 / ARM64 | `windows-amd64` / `windows-arm64` |
| macOS Intel / Apple Silicon | `darwin-amd64` / `darwin-arm64` |
| Linux x64 / ARM64 | `linux-amd64` / `linux-arm64` |

CLI 必须显式选目标，不由构建机系统推断。交叉编译成功不代表目标机实测通过。未嵌 JDK 时，运行期若设置了 `JAVA_HOME`，使用其中的 `bin/java`（Windows 为 `bin/java.exe`）；设置错误会失败，不回退 PATH。只有未设置 `JAVA_HOME` 才从 PATH 查找 Java，且要求满足业务最低版本。

### 5.3 内嵌 JDK、指定 Go、时间和参数

```text
java -jar xjar-plus-2.0.0.jar package --input app.jar --output dist/bundled --password-file private/password.txt --app-id demo --issuer-file private/demo.issuer --target windows-amd64 --jdk-zip jdk-windows-x64.zip --go-executable "C:/Program Files/Go/bin/go.exe" --not-before 2026-10-24T00:00:00Z --not-after 2027-10-24T00:00:00Z --jvm-arg=-Xms256m --jvm-arg=-Xmx512m --app-arg=--server.port=8080 "--app-arg=--label=含 空格"
```

Windows 路径仅为示例；macOS/Linux 换对应 Go 路径和目标 JDK。`--jdk-zip` 只接受 ZIP，不接受 tar.gz 或 JDK 目录，须包含完整 JDK、`release` 元数据及 `bin/java`（Windows 为 `bin/java.exe`）。可有一层统一顶层目录；macOS 的 JDK 根一般在 `.jdk/Contents/Home`，需提供可识别的根布局，不能仅凭外层名字判断。OS、架构或 Java 下限不匹配会失败。

每个 `--jvm-arg` / `--app-arg` 是一个 argv 元素，重复选项保留顺序。以 `-` 开头的值用等号形式，不把整串参数塞入一个值。含空格可引用完整选项；值本身不再加 shell 引号。参数在构建时写入二进制，可被客户端提取，不适合长期秘密。

时间为 UTC ISO-8601。`2027-10-24T00:00:00Z` 对应北京时间 2027-10-24 08:00。省略 `--not-before` 用请求创建时刻；省略 `--not-after` 为永久。起始含边界，到期不含；不用日期短串、空串或 `0` 表示永久。未来生效的许可证到时后需重启启动器才启动业务。

### 5.4 交给用户什么

标准输出 JSON 的 `output` 是实际启动器路径，以它所在目录为准。`--output` 是父目录，工具在下面生成安全命名的发行目录，不能用原始 `appId` 自行拼路径。客户目录包含启动器与同目录 `key.x`；业务加密 JAR、配置及可选 JDK 已嵌入二进制，无需另发原始 JAR、密码或 `.issuer`。

```text
dist/
└── app-<appId 的 UTF-8 字节 SHA-256，小写十六进制>/
    ├── launcher.exe  # Windows；macOS/Linux 名为 launcher
    └── key.x
```

父目录 `dist` 可以已经存在；同一 `appId` 的最终发行子目录不能存在。同一个父目录重复打包同一应用会冲突，升级构建请换新的父目录，例如 `dist/release-2`。

在目标机验证后交付整个发行目录。运行期在私有临时目录展开所需内容、退出时清理；临时盘需有足够空间。不支持原子提交的文件系统、权限或清理失败会报错，暂存文件不是成功产物。

<a id="license"></a>
## 6. 许可证签发、续期和应用升级

`package` 已生成初始 `key.x`，无需再 `license issue`。续期用最初的 `.issuer` 写到新文件，不需要原始 JAR、密码或 Go：

```text
java -jar xjar-plus-2.0.0.jar license issue --issuer-file private/demo.issuer --output renewal/key.x --not-before 2026-10-24T00:00:00Z --not-after 2028-10-24T00:00:00Z
```

永久证省略 `--not-after`，时间规则与打包一致。`license issue` 不接收 `--app-id`，应用标识从凭据读取。

1. 确认新证来自该应用原有凭据，保存部署端旧 `key.x` 恢复副本。
2. 停止启动器，把新证放到**实际启动器二进制旁**，名为 `key.x`。
3. 重启，查询授权有效且业务运行，再检查业务自己的就绪状态。

运行中不热加载许可证，替换文件不会自动恢复业务，必须重启；状态 API 没有上传许可证/重启的写接口。缺失、损坏、不匹配、未生效及过期证均不放行业务。

应用升级：新版原始 JAR 重新 `package` 到新目录，沿用相同 `appId` / `.issuer`。同一应用的许可证可跨构建继续使用。要保留原期限，把原有证部署到新启动器旁；不要误把打包默认生成的永久证当作原有期限。换应用标识或凭据后旧证不再匹配。

<a id="launcher"></a>
## 7. 启动器运行、端口和状态接口

进入发行目录后直接运行生成文件，文件名以构建 `output` 为准，以下用 `launcher` 代称：

```powershell
.\launcher.exe
```

```sh
./launcher
```

Unix 复制后如丢失执行权限，对**该文件**执行 `chmod u+x ./launcher`。macOS 下载信任提示按本机管理政策处理。

启动器不提供工具的 encrypt/package 子命令，运行时不解析 `--jvm-arg`，也不追加命令行业务参数；参数在打包时配置。stdin/stdout/stderr 继承给业务。Ctrl+C 结束启动器和受管进程；正常业务退出会回收临时内容并转交退出码。

### 7.1 多实例和状态端口

状态默认 `127.0.0.1:19527`。多实例分别指定不同端口；不影响业务端口。Windows PowerShell：

```powershell
$env:XJAR_PLUS_STATUS_PORT = '19528'
.\launcher.exe
```

macOS/Linux：

```sh
XJAR_PLUS_STATUS_PORT=19528 ./launcher
```

允许 `0..65535`，`0` 为系统分配，实际地址看日志。只绑定 IPv4 loopback，不能设置为 `0.0.0.0`。非法值或端口占用会失败。环境变量仅属于当前进程/终端，不是持久配置修改。

### 7.2 查询状态并检查业务就绪

Windows 使用 `curl.exe` 避免旧 PowerShell 同名别名；macOS/Linux 用 `curl`：

```text
curl --max-time 3 http://127.0.0.1:19527/xjp/healthz
curl --max-time 3 http://127.0.0.1:19527/xjp/license/status
```

`GET` / `HEAD /xjp/healthz` 只证明状态服务可访问，GET 正文 `ok\n`。`GET` / `HEAD /xjp/license/status` 返回快照，GET 为 JSON、`Cache-Control: no-store`。同机调用无需令牌；其他方法 405，未知路径 404。许可证无效也返回 HTTP 200，必须判断正文。

| 字段 | 含义 |
|---|---|
| `available` | 启动时成功读取、认证并匹配应用；已过期但可认证的证仍可能为 true |
| `valid` | 按当前时间已生效且未到期 |
| `appRunning` | 业务进程仍运行，不等于业务 HTTP 已就绪 |
| `validStartDate` / `validEndDate` | UTC RFC 3339 时间；不可用为空，永久证结束时间也为空 |
| `perpetual` | 已认证永久证；仍需检查 valid，可能尚未生效 |
| `remainingMs` | 有效有限证剩余毫秒；无效或永久为 0 |
| `remaining` | 可省略的展示文本，不作为程序判断条件 |
| `serverTime` | 本次采样 UTC 时间，依赖本机时钟 |
| `message` | 可省略人类说明，不是稳定错误码 |

合成响应示例（不是客户实测）：

```json
{"available":true,"valid":true,"validStartDate":"2026-10-24T00:00:00Z","validEndDate":"2026-10-25T00:00:00Z","perpetual":false,"remainingMs":3600000,"remaining":"01h 00m 00s","serverTime":"2026-10-24T23:00:00Z","appRunning":true}
```

检查顺序：服务可达 → `valid=true` 且 `appRunning=true` → 业务自己的就绪接口/主要功能。超时可间隔重试，连接失败先查进程/日志/端口，不能认作许可证过期。

运行中到期后停止业务，宽限截止为到期后 5 秒，必要时结束自有进程树。停止期间可短暂出现 `valid=false`、`appRunning=true`；确认退出后清理私有目录，状态服务继续。换证重启才重新运行；业务持久化不要依赖到期时一定能完成 shutdown hook。

<a id="reference"></a>
## 8. 全部命令、参数、输出与退出码

### 8.1 命令与必填项

全部命令支持 `-h` / `--help`，Picocli 也提供 `-V` / `--version` 帮助选项，版本查询推荐在根命令执行。表中“密码源”为第 1.2 节三选一；交互方式不需要额外选项。

| 命令 | 必填业务参数 | 其他可用参数 |
|---|---|---|
| `encrypt` | `--input`、`--output`，密码源 | `--include`、`--exclude`、`--encrypt-dependencies`、`--password-file`、`--password-stdin` |
| `package` | `--input`、`--output`、`--app-id`、`--issuer-file`、`--target`，密码源 | encrypt 全部可选项，再加 `--jdk-zip`、`--go-executable`、`--jvm-arg`、`--app-arg`、`--not-before`、`--not-after` |
| `inspect` | `--input` | 不读密码，不接受筛选 |
| `verify` | `--input`，密码源 | `--password-file`、`--password-stdin` |
| `decrypt` | `--input`、`--output`，密码源 | `--password-file`、`--password-stdin`；不接受筛选/依赖开关 |
| `doctor` | 无 | 无额外业务参数 |
| `license` | 选择 `init` 或 `issue` | 单独运行显示分组帮助 |
| `license init` | `--app-id`、`--output` | 不读归档密码 |
| `license issue` | `--issuer-file`、`--output` | `--not-before`、`--not-after`；不读归档密码 |
| `ui` | 无 | `--port`（默认 0）、`--no-browser`（默认 false），详见 [UI 手册](web-ui.md) |

### 8.2 参数值与默认行为

| 参数 | 值类型 / 默认 / 约束 |
|---|---|
| `--input` | 已存在的本地 JAR；encrypt/package 需原始可执行包，verify/decrypt 需 v2 加密包 |
| `--output` | encrypt/decrypt/init/issue 是新文件；package 是发行目录父目录。不得与输入相同，已有目标不覆盖 |
| `--include` | 可重复单条 glob；默认空列表，选中全部可处理应用内容 |
| `--exclude` | 可重复单条 glob；默认空列表，排除优先 |
| `--encrypt-dependencies` | 无值开关，默认 false；针对已识别嵌套依赖 |
| `--password-file` | 严格 UTF-8、最多 64 KiB 的私有文件路径 |
| `--password-stdin` | 无值开关，默认 false；显式读一行 UTF-8，与密码文件互斥 |
| `--app-id` | 稳定应用标识，1..256 UTF-8 字节；打包须与凭据匹配 |
| `--issuer-file` | 原有 `.issuer` 文件路径，不是 `key.x` |
| `--target` | 六个 OS/架构值之一，CLI 必填，见第 5 节 |
| `--jdk-zip` | 可选完整目标 JDK ZIP；默认不内嵌 |
| `--go-executable` | 可选 Go 可执行文件路径；默认 PATH，要求 1.24+ |
| `--jvm-arg` | 可重复一项 JVM 参数，默认空列表，保留顺序 |
| `--app-arg` | 可重复一项业务参数，默认空列表，保留顺序 |
| `--not-before` | 可选 ISO-8601 instant，默认请求创建时刻，含边界 |
| `--not-after` | 可选 ISO-8601 instant，默认永久，到期边界不含，不能早于生效 |
| `--port` | UI 端口 0..65535，默认 0；不是 Go 状态端口 |
| `--no-browser` | UI 无值开关，默认 false；服务仍运行，只不自动打开浏览器 |

没有自定义算法、`--force`、`--password`、`--package-name`、`--config` 批处理或 CLI 许可证热更新选项。算法固定 AES-256-GCM，选择包名使用 include/exclude。

### 8.3 输出与退出码

成功结果写 stdout，进度/错误写 stderr，UTF-8 编码；采集 JSON 时不要合并 stderr。加密/恢复/打包/凭据创建/签发的结果形如：

```json
{"output":"/actual/output/path","artifactId":"build-identity-or-app-id","warnings":[]}
```

`output` 为实际位置；`artifactId` 为操作返回标识，不作为路径/密钥解析；`warnings` 为警告列表。`inspect/verify` 输出 `kind`（`PLAIN`/`BOOT_CLASSIC`/`BOOT_MODERN`/`SOLON`）、`mainClass`、`encrypted`、`entries`、可空的 `minimumJava`；条目计数包含运行时/元数据，不等于选中的业务文件数。

通常成功/帮助退出码为 0，业务或认证失败为 1，参数解析错误（必填项缺失、未知选项等）为 2；Ctrl+C/系统终止的退出码由环境决定。归档操作支持协作取消，按 Ctrl+C 后等待清理，不手工删除运行中的暂存区。启动器运行期转交业务退出码，与工具 CLI 结果不同。

Go 启动器的准备/监听/进程管理失败为 1，内嵌配置或 payload 损坏为 2，Ctrl+C 为 130，Unix SIGTERM 为 143；业务自行退出时传播业务退出码。无效许可证和过期状态会继续保留状态服务，不是立即退出并返回一个“许可证错误码”。

<a id="troubleshooting"></a>
## 9. 常见问题和重试

| 现象 | 检查及处理 |
|---|---|
| 指定包后成功了，类却还能直接读 | 检查点号转 `/`、Boot 前缀、真实包名、MR 路径；verify 通过不能证明 include 命中 |
| 业务依赖没有变 | 需依赖开关及 `container.jar!/包路径`；检查是否签名依赖 |
| 多个包只处理一个 | 重复 `--include`，不要用空格/逗号把多个路径塞成一个值；引用 glob |
| unknown option / missing parameter | 对该子命令执行 `--help`；以 `-` 开头的参数值使用等号形式 |
| IDE/CI 没有控制台 | 用密码文件；业务启动用 `-Dxjar.plus.password-file=...`，不是工具的 `--password-file` |
| 密码看似一样但验证失败 | 检查 UTF-8/BOM、空格、多余换行和 Unicode 差异；不要打印密码排查 |
| Output already exists | 先确认已有文件是不是上次成功结果，再选新目标；无覆盖开关 |
| 原地加解密被拒绝 | 保留输入，换输出；不能用符号链接/硬链接别名覆盖 |
| 已保护 / 旧格式输入被拒绝 | 不重复加密，用原始 JAR；v1 密文/旧 key.x 不能交给 v2 |
| signed archive / signature 被拒绝 | 用自己构建的未签名原始包，或保持该签名依赖不改；不自动去签名 |
| Windows 中文/emoji 参数损坏 | 核对 JDK 原生代码页；file.encoding 不能修复原生 argv，考虑 UI/API 与配置文件传业务文本 |
| Go 缺失/过旧 | doctor 看兼容字段；选择 Go 1.24+，可指定 --go-executable |
| Go 依赖下载失败 | 核对 Go 环境、模块源与缓存；工具不自动改系统代理 |
| JDK 归档不匹配 | 选择目标 OS/架构/足够版本的完整 ZIP，核对 release 和布局 |
| 启动器存在但业务不启动 | 查日志及 status 正文：证可能缺失/无效/未生效/过期，或 Java 不可用 |
| 换 key.x 没效果 | 确认在实际二进制旁而非另一个工作目录，重启并重新查询 |
| 第二个实例启动失败 | 指定新的状态端口，业务端口也按应用自身配置避让 |
| healthz 200 但业务不可用 | 检查 valid、appRunning，再检查业务自己的就绪状态 |
| 失败/取消后重试 | 检查是否已提交最终输出；新输出、重新供给密码，不用暂存半成品 |
| UI 刷新后表单/任务丢失 | 见 [UI 恢复说明](web-ui.md)，先检查输出，不盲目重建 |

独立 JAR 不做授权限制；需要到期停止选 Go 模式。AES 和客户端许可证不能保证抵御客户端管理员提取密钥、改程序或回拨时钟。V1 迁移用对应旧工具先恢复并验证原始 JAR，再用 v2 重建；没有旧密文/许可证自动转换命令。
