<!-- @author kongweiguang -->

# XJar Plus 2.0

用 **AES-256-GCM** 保护可执行 Java JAR。支持 Java `main()`、命令行和本地网页，三种入口共用同一套核心逻辑。

| 产物 | 适用场景 | 构建要求 |
|---|---|---|
| 独立加密 JAR | 输入密码启动，不做许可证或到期限制 | Java 21 |
| Go 授权启动器 | 许可证、到期停机，可选内嵌 JDK | Java 21 + Go 1.24+ |

工具需要 Java 21；**目标应用不被强制升级到 Java 21**，仍按原应用和适配器要求选择 JDK。目标机器不需要 Go。

Windows、macOS、Linux 共用同一个工具 JAR；macOS 不需要安装 PowerShell。

## 使用文档

第一次使用先看 [从下载到运行的使用指南](docs/usage.md)。以下是完整手册，普通用户也能直接阅读，**不需要安装 skill 才能看**：

| 想做什么 | 打开这里 |
|---|---|
| 指定包名、多个包、排除 DTO/配置、加密业务依赖 | [筛选规则与可复制示例](.agents/skills/xjar-plus/references/commands.md#selection) |
| CLI 所有命令、全部参数、默认值与密码输入 | [CLI 完整手册](.agents/skills/xjar-plus/references/commands.md) |
| 在测试类 main() 编码加密、打包、签发许可证 | [Java API / SDK 手册](.agents/skills/xjar-plus/references/java-api.md) |
| 网页每个字段、选文件、取消重试、配置导入导出 | [Web UI 手册](.agents/skills/xjar-plus/references/web-ui.md) |
| 分发给客户、内嵌 JDK、续期升级、状态接口 | [Go 打包](.agents/skills/xjar-plus/references/commands.md#package) · [许可证](.agents/skills/xjar-plus/references/commands.md#license) · [部署运行](.agents/skills/xjar-plus/references/commands.md#launcher) |
| 报错、不知道哪里填错了 | [常见问题](.agents/skills/xjar-plus/references/commands.md#troubleshooting) |
| 让 AI 根据自己的项目协助操作 | [XJar Plus skill](.agents/skills/xjar-plus/SKILL.md) |

## 快速开始

下载 [xjar-plus-2.0.0.jar](https://github.com/kongweiguang/xjar-plus/releases/download/v2.0.0/xjar-plus-2.0.0.jar)（[全部附件](https://github.com/kongweiguang/xjar-plus/releases/tag/v2.0.0)），或在源码根目录构建：

```text
mvn -pl xjar-plus-app -am package
```

本地构建的工具位于 `xjar-plus-app/target/xjar-plus-2.0.0.jar`。下面命令假设已进入工具 JAR 所在目录。

### 网页操作

```text
java -jar xjar-plus-2.0.0.jar ui
```

网页只监听本机。选择模式、原始 JAR、输出位置并填写密码，再开始处理；支持进度、取消、失败重试和非敏感配置导入导出。无参数只显示帮助，不会自动启动网页。

### 命令行加密

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-encrypted.jar
java -jar app-encrypted.jar
```

按终端提示输入密码。自动化可用 `--password-file password.txt`；启动加密应用时用 `java "-Dxjar.plus.password-file=password.txt" -jar app-encrypted.jar`，不要把明文密码写进命令行。

### 只加密指定包

例如只保护 `com.example.order`，**匹配的是 JAR 内路径，点号要换成 `/`**。先运行 `jar tf app.jar` 查看结构，再选择对应命令。

普通 JAR：

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-encrypted.jar --include "com/example/order/**"
```

Spring Boot / 标准嵌套布局 Solon：

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-encrypted.jar --include "BOOT-INF/classes/com/example/order/**" --exclude "BOOT-INF/classes/com/example/order/dto/**"
```

两个例子分别使用，不要写到同一个已存在的输出。多个包重复 `--include`；排除优先，未选中文件保留明文，不会删除。依赖默认不加密；其开关和 `依赖.jar!/包路径` 写法见 [完整筛选教程](.agents/skills/xjar-plus/references/commands.md#selection)。

### 在测试类 main() 中使用

引入 SDK 中的 `io.github.kongweiguang.xjar.plus:xjar-plus-core:2.0.0`，参考可直接运行的 [EncryptMain.java](examples/EncryptMain.java)。无需修改工具内部实现；[Java API 手册](.agents/skills/xjar-plus/references/java-api.md) 说明 SDK 安装、Maven 依赖、所有请求参数、筛选、进度和取消。

### 打包 Go 授权启动器

```text
java -jar xjar-plus-2.0.0.jar license init --app-id demo --output demo.issuer
java -jar xjar-plus-2.0.0.jar package --input app.jar --output dist --app-id demo --issuer-file demo.issuer --target windows-amd64
```

同一应用升级、续期复用原 `appId` 和 `.issuer`。凭据由发布者保管，**不要发给客户或提交 Git**。可加 `--jdk-zip jdk.zip` 内嵌匹配平台的 JDK；不内嵌时使用目标机器的 Java。

macOS 目标选择 `darwin-arm64`（Apple Silicon）或 `darwin-amd64`（Intel）；Linux 选择 `linux-arm64` 或 `linux-amd64`。

## 支持范围

- 普通可执行 JAR、uber JAR：需要有效的 `Main-Class`。
- Spring Boot 2.x / 3.x / 4.x、Solon 4.x：标准可执行 JAR 布局。
- 不支持 WAR、Native Image、模块路径启动和任意自定义 Launcher。

具体固定版本、实测 JDK/平台和框架原生限制见 [兼容矩阵](docs/compatibility-matrix.json) 与 [使用指南](docs/usage.md)。交叉编译成功不代表目标平台已实测。

**2.0 不兼容旧 API、旧密文或旧 `key.x`**，请从原始 JAR 重新构建。加密不等于无法逆向，也不能保证客户端许可证不可绕过。

## 文档与开发

- [使用指南](docs/usage.md)：下载、首次操作顺序、三种入口及完整手册导航。
- [架构说明](docs/architecture.md) · [加密格式](docs/format-v2.md) · [可安装 Skill](.agents/skills/xjar-plus/SKILL.md)。
- `xjar-plus-*`：五个 Java 模块；`launcher/`：Go 启动器；`examples/`：Java 示例。
- `integration-tests/`：框架与跨语言验证；`scripts/`：统一的 Python 检查、验收和发布脚本。

开发验证：`mvn -Pquality verify`、`python scripts/check_source.py`、`python scripts/check_architecture.py`。本地生成发布附件：`python scripts/release.py`；不会自动提交、推送或发布 GitHub。

维护脚本使用 Python 3.11+；macOS/Linux 通常将上述 `python` 写为 `python3`，脚本文件完全相同。

基于原 [xjar](https://github.com/core-lib/xjar) 二次开发，保留原项目来源与许可声明。许可证：[Apache-2.0](LICENSE)，补充声明：[NOTICE](NOTICE)。
