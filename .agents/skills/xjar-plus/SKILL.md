---
name: xjar-plus
description: 使用 XJar Plus 2.0 加密可执行 Java JAR、打包 Go 授权启动器、续期许可证，或排查 CLI、Java main() API 和本地 Web UI。仅用于 XJar Plus 工作流，不用于通用密码学或无关 Java 打包。
---

<!-- @author kongweiguang -->

# XJar Plus 2.0

帮助用户构建并验证受保护的 Java 分发件。优先采用用户选定的入口：测试类 `main()`、`java -jar` 命令行或显式 `ui` 子命令。

## 详细手册与读取顺序

本 skill 同时附带可供用户直接阅读的中文手册。按本次任务读取对应文档，不用一次加载全部：

| 用户要做的事 | 需要读取的参考 |
|---|---|
| CLI 加密、指定包/类/资源、多包排除、业务依赖加密 | [commands.md](references/commands.md)，重点是[筛选教程](references/commands.md#selection)与参数表 |
| Go 打包、平台/JDK、首次凭据、续期升级、部署和状态排障 | [commands.md](references/commands.md#package) 的第 5–9 节 |
| 测试类 main() / Maven SDK 集成、进度取消 | [java-api.md](references/java-api.md)；涉及筛选再读 commands.md 的第 2 节 |
| Web UI 填表、文件选择、配置导入导出、任务取消/恢复 | [web-ui.md](references/web-ui.md)；需要生成后运行/续期时再读 commands.md |

用户说“不会用”时，先给能替换路径直接运行的示例，说明输入、输出、密码来源和成功后的下一步；不要只列选项名或让用户自己读 `--help`。需要精确参数时使用已安装版本的帮助与本手册核对，不把未实现选项编造为解决方法。

## 先确认产物模式

- **独立加密 JAR**：原始可执行 JAR + 密码即可。目标 Java 须支持原应用及 AES-256-GCM；不需要 Go、内嵌 JDK、许可证或状态服务，也不执行到期限制。
- **Go 授权启动器**：构建机还需要 Go 1.24+、明确的目标平台、稳定的 `appId` 和发布者原有的 `.issuer` 凭据。可选内嵌匹配平台的 JDK ZIP，否则使用目标机器 Java；目标机器不需要 Go。同一应用只初始化一次凭据，升级与续期必须复用。

工具本身需要 Java 21。普通可执行/uber JAR 必须具有有效 `Main-Class`。Boot classic/modern 与 Solon 嵌套布局采用不同适配器；以发布件的兼容矩阵为准，不把计划支持或交叉编译等同于实机通过。WAR、Native Image、JPMS 启动和自定义 launcher 不在范围内。V2 不接收 V1 密文或 `key.x`，需从原始 JAR 迁移。

## 执行最小闭环

1. 运行 `java -jar <tool.jar> doctor` 和 `inspect --input <original.jar>` 检查工具环境与归档结构，记录原应用所需 JDK/参数。两命令不会启动原应用；还需按其原启动方式在合适的测试环境验证原包能正常运行。
2. 选择与所有输入不同的新输出路径；不要通过删除或覆盖已有应用、凭据来让重试通过。
3. 优先控制台输入密码，或请用户提供本地密码文件**路径**，不要求将密码、凭据内容粘贴到聊天。工具自动化支持 `--password-stdin`；加密应用的标准输入仍留给业务。
4. 按选定入口构建。CLI、筛选、部署和续期看 [commands.md](references/commands.md)，Java API 看 [java-api.md](references/java-api.md)，网页看 [web-ui.md](references/web-ui.md)。include/exclude、JVM/业务参数逐项传递，不拼接 shell。
5. 对新归档执行 `verify`，再在目标 JDK/平台实际启动分发件，检查主要业务流程与加密资源，不能只检查进程创建。失败时保留原文件，说明失败阶段、安全重试方式和已验证边界。

网页仅通过 `ui` 启动，无参数只显示帮助。选择文件只回填路径，不自动构建；取消保留原路径。原生选择器不可用时改用手动路径，不更换工具包或修改系统设置。刷新只恢复后台任务状态，不恢复表单或密码；任务状态丢失时先检查输出再明确新建，不能自动重放请求。

## 指定包名时必须落实的规则

1. 先读取 `jar tf <input.jar>` 或只读 ZIP 目录，确认类的真实路径。点号包名不能直接当 glob。普通 JAR 的 `com.example.order` 通常为 `com/example/order/**`；Boot / 标准嵌套 Solon 通常为 `BOOT-INF/classes/com/example/order/**`，不替用户猜布局。
2. 多个 include 是 OR，exclude 优先；未选中保留明文，不会被删除。CLI 每条规则重复选项并引用 glob，Java 每条调用一次 builder，网页每条一行且不加命令行引号。
3. 依赖必须同时开启依赖加密，并匹配完整 `container.jar!/包路径`；普通 uber JAR 的平铺类按包筛选，不能按 Maven 坐标找来源。注意签名依赖拒绝重写。
4. `**/` 不自动表示零层目录；根默认包、Multi-Release 覆盖类需按实际路径另配规则。不能把构建或 verify 成功当作规则已经命中的证明；抽查目标 class 与未选条目的加密/透传结果，再实际启动验证。

例如用户说“我的 Spring Boot 包只加密 com.example.order，排除 dto”，在路径已确认后应给出完整形态：

```text
java -jar xjar-plus-2.0.0.jar encrypt --input app.jar --output app-encrypted.jar --password-file private/password.txt --include "BOOT-INF/classes/com/example/order/**" --exclude "BOOT-INF/classes/com/example/order/dto/**"
java -jar xjar-plus-2.0.0.jar verify --input app-encrypted.jar --password-file private/password.txt
java "-Dxjar.plus.password-file=private/password.txt" -jar app-encrypted.jar
```

说明 `private/password.txt` 是用户本地准备的私有 UTF-8 文件，且输出须为新文件。调用方未选入口时，可根据“想写代码 / 想用终端 / 想填网页”建议入口；未选授权模式时解释两模式差异，不能把独立 JAR 描述成有到期监督。

## 秘密与安全边界

密码和发行凭据不得写入日志、客户包、公共 Release 或 UI 导出配置；JVM/业务参数也可能含秘密，默认不导出。真实客户 JAR/JDK 不进入公共产物。不要修改 `.issuer` 或猜测、恢复密钥。初始化/签发许可证是单独的用户操作，普通诊断不自动授权它。

缺失、无效、尚未生效或过期许可证会阻止启动业务。业务停止后本机状态服务可能仍可访问；HTTP 可达不代表授权有效或业务就绪。续期使用同一发行凭据，明确替换部署中的 `key.x` 后重启。

AES 授权不保证抵御客户端密钥提取、修改启动器或系统时钟；独立 JAR 有意不执行许可证限制。

回报时提供不含秘密的命令、产物位置、实测版本/平台及未覆盖检查。不自动提交、推送、发布 Release、安装软件或向外发送产物。
