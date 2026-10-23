<!-- @author kongweiguang -->

# XJar Plus 2.0 架构说明

本文描述工作区中的 2.0 分层与运行边界，不代替使用说明。固定框架版本、Windows 11 amd64 的 24
项真实归档验收结果及未覆盖平台请看 [`compatibility-matrix.json`](compatibility-matrix.json)。

## 入口与应用边界

三种入口只负责把外部输入转换成统一请求，不各自实现加密或授权规则：

```text
Java API / CLI / 本地 Web UI
              │
              ▼
       core 应用用例与领域规则
          ┌───┴────┐
          ▼        ▼
   加密可执行 JAR   Go 启动器发行包
```

| 组件                            | 主要职责                                                      |           Java 基线 |
|---------------------------------|---------------------------------------------------------------|--------------------:|
| `xjar-plus-format`              | AES-GCM、密码 KDF、密钥信封、索引、分块流和 XJI2/XJL2 编码    |         8，JDK-only |
| `xjar-plus-runtime`             | 目标应用运行时、共享认证读取及普通/经典布局适配               |                   8 |
| `xjar-plus-runtime-boot-modern` | Boot modern 加载器薄适配                                      |                  17 |
| `xjar-plus-core`                | 领域规则、用例编排、归档事务、运行时装配、Go 打包和许可证用例 |                  21 |
| `xjar-plus-app`                 | Picocli 命令行、本地 UI、参数/密码输入、任务展示              |                  21 |
| `launcher/`                     | Go 进程监督器、授权状态服务、目标 JDK 准备及退出清理          | Go module 声明 1.24 |

包名前缀统一为 `io.github.kongweiguang.xjar.plus`，Maven groupId 为 `io.github.kongweiguang.xjar.plus`。工具构建依赖 Java
21；注入目标应用的只有其归档家族需要的格式和运行时类，不注入 CLI/UI 或 Go 构建工具。目标应用的可运行 JDK 由应用字节码与所选适配器决定。

## 轻量 DDD 分层

- **Interfaces**：API 门面、CLI 命令、Web 路由和任务输入；负责解析路径、参数和秘密输入，不承载归档算法或许可证裁决。
- **Application**：统一编排 inspect、encrypt、decrypt、verify、package、issuer 创建和 license 签发等用例；处理预检、进度、取消和事务顺序。
- **Domain**：归档和启动器命令、过滤策略、appId/时间边界等业务约束；不依赖命令行、网页或操作系统实现。
- **Infrastructure**：JAR/ZIP 读写、运行时资源装配、文件暂存、Go 工具调用、目标平台检查和进程/文件适配。

层次集中在现有 Maven 模块中，不为每一层单独拆 Maven module。Go 侧按 `cmd/launcher` 和 `internal`
划分进程入口、配置、license、status、broker、runner、process 等小边界；不机械复制 Java 的逐类接口/实现结构。

工具端统一使用 Jackson 3：core 只用流式生成器写 Go 配置，app 负责 CLI/UI JSON 边界；格式与目标运行时不引入 JSON 库。维护脚本集中在 `scripts/`，使用 Python 标准库；框架测试与独立跨语言探针分别位于 `integration-tests/` 和其 `security/` 子目录。运行工具 JAR 不依赖 Python。

## 归档处理与加载路径

处理器读取可执行 JAR 的 manifest 和条目，校验路径与重复项，识别归档家族，再应用同一套 include/exclude 规则。可识别的家族是普通
JAR、Boot classic、Boot modern 和 Solon 默认嵌套 JAR。输入必须有受支持的可执行入口；普通依赖 JAR、WAR、Native
Image、模块路径以及任意自定义 Launcher 不属于当前目标。

加密结果保留启动所需元数据，包含认证的密钥信封和条目索引。默认只保护应用内容，嵌套依赖默认保留；显式开启依赖加密才处理相应嵌套归档。外层签名
JAR 一律拒绝，因为 manifest 和运行时注入总会改动该归档；签名依赖只有在实际需要重写时才拒绝，不静默保留失效签名声明。

Java 下限取所选适配器下限和已扫描基础 class 字节码版本的较大值。扫描包含外层应用与已识别标准 lib 目录中的一层嵌套依赖，不用
MR overlay 或 `module-info.class` 抬高基础运行版本。加密归档的无密码 `inspect` 返回未知下限，`verify`
从认证流扫描后才报告版本，不能把密文头当成旧版 class；这项预检不能替代在目标 JDK 的实际运行验证。

运行时先取得数据密钥，校验完整索引后才建立应用加载器。class 和资源按归档路径查找认证元数据；分块在标签验证后才向调用方交付明文。普通、Boot
和 Solon 适配器保留各自启动器/嵌套布局差异，不能仅用一个版本判断替代加载器契约。密码模式支持隐藏 Console 输入或显式
`-Dxjar.plus.password-file=<path>`；它不会读取应用 stdin。协议字节、长度上限、AAD 和失败规则见 [
`format-v2.md`](format-v2.md)。

普通 JAR 使用系统类加载器桥接，使不继承线程上下文加载器的 JDK 工作线程也能通过认证路径读取 SPI 和资源。独立 JAR
未显式配置该桥接时，入口先用当前 Java、原 JVM/业务参数启动一次子 JVM，等待并转交退出码；显式配置桥接时直接运行。Go 对普通 JAR
自动配置桥接，因此业务只启动一个 JVM。Boot/Solon 保留自身加载器，不采用这个重新启动路径。自定义系统类加载器和不能安全重复启动的
JVM 选项不属于透明兼容范围，限制与直接启动方式见使用指南。

Boot 布局按实际 launcher、launched class loader 和 archive 类组合识别。3.2 的官方 CLASSIC 兼容入口虽使用
`.launch.JarLauncher` 名称，仍进入经典适配器。经典 loader 的 Multi-Release
条目通过其实际物理路径匹配认证索引，不按逻辑名称、长度或最高版本猜测；不满足已知契约时直接拒绝。

索引中的明文资源也受完整性保护：`getInputStream()` 返回前以固定 8 KiB 缓冲完整核验长度、CRC32 与
SHA-256，成功后重新打开独立响应流。因此部分读取、SPI 与 Range 请求不会先收到篡改内容；代价是明文资源的两遍 I/O
和首字节延迟，不缓存完整资源、不写解密副本。密文仍逐块认证后交付；HEAD/长度查询使用认证索引元数据。

工具端验证嵌套依赖时，同一操作内每个容器只复制、认证一次，并在私有工作区复用其原始容器副本；最多同时持有 4 个 `JarFile`
，优先关闭闲置句柄。该副本仍包含原有密文，不是整包解密落盘。关闭验证上下文时清理副本和句柄，密钥在 finally
中清零；取消检查贯穿复制与覆盖校验，避免按每个 class 重复扫描整份依赖。

## 两种发行模式

### 独立加密 JAR

加密后的 JAR 自带对应运行时入口；用户通过密码解锁数据密钥，运行时按需认证读取内容。这个模式没有许可证签发、到期监督或状态
HTTP 服务，也不承诺运行时明文对宿主机管理员不可见。

### Go 启动器

Java core 在私有暂存目录准备加密 JAR、启动器配置和可选 `jdk.zip`，再调用 Go 编译器生成目标可执行文件。Go launcher
配置随二进制嵌入，包含供运行时装载使用的密钥材料和参数；它不等于不可提取的远程密钥托管。最终 `key.x` 独立放在启动器旁边。发行方
`.issuer` 只参与本地打包和续期，不进入最终客户目录或公共发布件。

启动器可内嵌 JDK；若不内嵌，则先检查 `JAVA_HOME`，再查找 PATH 中的 Java，并探测是否可运行。内嵌 JDK 需符合选择的
OS/架构；解压到私有临时目录。Go 用进程 argv 数组传递 JVM/业务参数，不拼接 shell 字符串；标准输入、输出和错误流继承业务进程。

launcher 将许可证校验与业务进程状态分开：缺失、损坏、绑定不匹配、尚未生效或到期时不启动业务，但状态服务仍可用；运行中到期时停止受管业务进程树。更换旁路
`key.x` 不会改变已经运行的授权快照，必须重启应用。Windows 使用 Job Object，Unix 使用进程组管理子进程清理；实际平台运行仍须通过相应矩阵验收。

到期停止的宽限截止点是许可证到期时间加 5 秒，不因轮询或停止请求延迟而重新计算 5 秒。确认业务退出后立即清理私有应用/JDK
目录，状态服务仍可查询。停止信号是尽力请求，不保证应用的 shutdown hook 必然执行：Windows 11 的本次 Java 21 实测中 Ctrl-Break
产生线程转储，最终由超时后的 Job Object 强停完成；业务数据持久化不能依赖到期时一定有优雅退出机会。

Go→Java 数据密钥交接通过短时、一次性的 loopback broker。broker 使用随机本机端口与一次性认证值；子 JVM 的环境中包含
rendezvous 元数据，但数据密钥不会放进命令行或长期秘密文件。通道仅服务启动交接，与状态 HTTP 服务分离。独立 JAR 密码模式不会占用
stdin。

## 状态服务与 Web UI

Go 启动器状态服务只监听 IPv4 loopback，默认端口 `19527`。可用 `XJAR_PLUS_STATUS_PORT` 选择 `0`（系统分配）或 `1..65535`
；该设置不更改绑定主机。值非法或端口绑定失败时启动失败。

| 路径                  | 方法      | 作用                        |
|-----------------------|-----------|-----------------------------|
| `/xjp/healthz`        | GET、HEAD | 状态服务健康；GET 返回 `ok` |
| `/xjp/license/status` | GET、HEAD | 当前许可状态和业务进程状态  |

状态 JSON 字段含 `available`、`valid`、`validStartDate`、`validEndDate`、`perpetual`、`remainingMs`、`serverTime`、`appRunning`
；剩余时长文本 `remaining` 和说明 `message` 可省略。`valid` 是当前时间下的许可证判断，`appRunning`
是业务子进程状态；有效许可证不代表业务应用一定已启动。状态接口只读，不返回许可证密钥、密码、私有凭据或启动参数。

本地 Web UI 与 Go 状态接口是不同服务：UI 由用户显式通过 `xjar-plus ... ui` 启动，使用系统分配端口（默认 `--port 0`
），只绑定回环。UI API 使用会话令牌、精确 Host/Origin 和同源限制；任务后台有并发、队列、请求体和保留时间上限。浏览器在提交前生成随机
UUID，仅将这个 ID 留在标签页的会话存储中；后端在任务记录仍保留时按 ID
去重。刷新和响应丢失只触发状态查询，不自动重放含秘密的请求；记录丢失时明确要求检查输出后再新建，不引入持久队列或数据库。任务执行异常也需进入终态并释放秘密引用，不能长期停留在
RUNNING。

文件选择请求由浏览器发至本机 Java 后端，再按当前 OS
调用可用系统选择器；未完成真实桌面视觉/焦点验收的平台只能标为待验证。详细操作看 [`usage.md`](usage.md)。

## 输出事务、失败处理与安全边界

加密、解密和启动器构建都从私有暂存输出开始；输入与输出不能相同，处理完成后验证暂存产物，再提交到目标位置。调用方可提供进度与取消探针。加密或认证失败不得回退明文；失败或取消不应把半成品报告为成功。文件系统、磁盘和进程异常仍需由集成测试覆盖，并按当前实现结果持续修订此说明。

提交使用操作系统原生的原子、不覆盖移动；同一输出并发提交只允许一个成功，不用容易遗留的锁文件。平台或文件系统不支持时明确失败，不降级为覆盖或复制。Go
编译缓存、临时配置与构建中间件都限制在私有暂存区，提交前先清理，避免密钥材料滞留共享构建缓存。清理失败会报告具体暂存路径，不能假装成功；这不构成断电后的持久性保证。

AES-GCM 能提供密文完整性与保密性，不保证客户端无法提取运行时密钥/明文、修改程序、伪造本地状态或回拨时钟。Go
本机授权是客户端执行策略，不应宣传为无法破解或强制 DRM。独立加密 JAR 不运行许可证逻辑。完整安全/协议约束见 [
`format-v2.md`](format-v2.md)。

## 验收状态

兼容清单 [`compatibility-matrix.json`](compatibility-matrix.json) 分别记录 Windows 11 amd64 本地候选和 Linux 提交 `10dd1a8` 的 24 项真实归档验收，均零失败；不是从 V1 历史结果推断，也不能替代最终 tag 的验证。Boot 2.1–2.3 的原生 multipart
长度错误以明确的基线一致性断言保留，不宣称这些框架缺陷已经修复。完整边界见使用指南。

Windows Go 启动器已实测无效授权、到期停机、状态服务存活、换证重启、单 JVM、参数/stdin 和私有目录清理；WSL Linux 已实测加密及真实
Go 编译阶段的 SIGTERM 取消。它们不等于 macOS/ARM 实机验收，也不覆盖原生选择框的高 DPI 与焦点交互。发布工作流包含质量检查、真实归档测试、Go
多平台构建与 Release 附件哈希回读；正式发布状态与最终 tag 验收以 [Release](https://github.com/kongweiguang/xjar-plus/releases/tag/v2.0.0) 和对应 [workflow 记录](https://github.com/kongweiguang/xjar-plus/actions/workflows/release.yml) 为准。
