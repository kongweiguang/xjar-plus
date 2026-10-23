#!/usr/bin/env python3
# @author kongweiguang
"""在本地事务目录生成、审查并校验 XJar Plus 公开发布附件。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Sequence

from _delivery_common import ChineseArgumentParser, ChineseHelpFormatter, resolve_executable, run_checked


GROUP_ID = "io.github.kongweiguang.xjar.plus"
SDK_MODULES = (
    "xjar-plus-format",
    "xjar-plus-runtime",
    "xjar-plus-runtime-boot-modern",
    "xjar-plus-core",
)
SDK_EXAMPLES = ("IssuerMain.java", "EncryptMain.java", "PackageMain.java", "LicenseMain.java", "README.md")
FORBIDDEN_NAMES = re.compile(
    r"(?i)^(?:\.env(?:\..*)?|key\.x|credentials?(?:\.(?:json|ya?ml|properties|txt))?|"
    r"secrets?(?:\.(?:json|ya?ml|properties|txt))?|[^/\\]+\.issuer|"
    r"issuer(?:[-_.](?:key|credentials?))?\.(?:key|pem|p12|pfx|json)|"
    r"jdk(?:[-_][^.]+)?(?:\.zip|\.7z|\.tar(?:\.gz)?|\.tgz)?|"
    r"(?:customer|client)-app(?:[-_.][^/]*)?\.jar)$"
)
FORBIDDEN_ARCHIVE_ENTRY = re.compile(
    r"(?i)(?:^|/)(?:\.env(?:\.[^/]*)?|key\.x|credentials?(?:\.(?:json|ya?ml|properties|txt))?|"
    r"secrets?(?:\.(?:json|ya?ml|properties|txt))?|[^/]*\.issuer|"
    r"issuer(?:[-_.](?:key|credentials?))?\.(?:key|pem|p12|pfx|json)|"
    r"jdk(?:[-_][^/]*)?\.(?:zip|7z|tgz|tar(?:\.gz)?)|"
    r"jdk(?:[-_][^/]*)?/(?:Contents/Home/|Home/)?(?:release|bin/java(?:\.exe)?)|"
    r"(?:customer|client)-app(?:[-_.][^/]*)?\.jar|payload/config\.json)(?:/|$)"
)
COMPATIBILITY_NOTE = """<!-- @author kongweiguang -->
# XJar Plus 兼容性说明

相邻 JSON 文件记录锁定的框架版本、Java 基线、归档布局、验证状态和各项证据。请结合证据与平台范围阅读结果：本地验证只适用于对应运行；交叉编译不等于在目标操作系统和架构上实际运行。

只有对应发布 tag 的兼容性工作流在声明的 JDK 上通过真实可执行 JAR 测试，才表示该框架版本已由该 tag 的 CI 验证。本地验证不能证明 tag CI 已通过；仅构建 fixture 也不算运行证据。使用某个框架版本或平台组合前，请核对对应 tag 的 GitHub Actions 运行结果及 JSON 证据。
"""


SDK_INSTALLER_TEMPLATE = r'''#!/usr/bin/env python3
# @author kongweiguang
"""把 SDK 压缩包中的 Maven 坐标安装到本机仓库，不执行远程发布。"""

from __future__ import annotations

import argparse
import os
import signal
import shutil
import subprocess
import sys
import time
from pathlib import Path


VERSION = "__VERSION__"
MODULES = ("xjar-plus-format", "xjar-plus-runtime", "xjar-plus-runtime-boot-modern", "xjar-plus-core")
MAVEN_TIMEOUT_SECONDS = 180


class ChineseFormatter(argparse.HelpFormatter):
    """將 SDK 安装器的标准参数布局保留并本地化用法标题。"""

    def add_usage(self, usage, actions, groups, prefix=None):
        """以中文呈现命令用法，方便 SDK 用户直接在终端查看。"""
        return super().add_usage(usage, actions, groups, prefix or "用法：")


class ChineseParser(argparse.ArgumentParser):
    """为生成的安装器提供中文选项标题和错误文本。"""

    def __init__(self, *args, **kwargs):
        """不改 argparse 的退出码语义，只调整人类可读说明。"""
        kwargs.setdefault("add_help", False)
        super().__init__(*args, **kwargs)
        self._optionals.title = "选项"
        self.add_argument("-h", "--help", action="help", help="显示此帮助信息并退出。")

    def error(self, message):
        """报告无效参数并使用标准参数错误退出码。"""
        self.print_usage(sys.stderr)
        self.exit(2, f"{self.prog}: 错误：{message}\n")


def windows_batch_command(executable: str) -> bool:
    """判断 Windows Maven 命令是否需要由系统 shell 启动。"""
    return os.name == "nt" and Path(executable).suffix.casefold() in (".cmd", ".bat")


def command_argv(executable: str, arguments: list[str]) -> list[str]:
    """保留 argv 结构，并拒绝批处理参数中的 cmd 百分号展开语法。"""
    if windows_batch_command(executable) and any("%" in token for token in (executable, *arguments)):
        raise ValueError("Windows 批处理命令参数不能包含 '%'，以避免 cmd.exe 环境变量展开。")
    return [executable, *arguments]


def parse_args() -> argparse.Namespace:
    """解析本机 Maven 命令，避免把可执行文件或参数拼接成 shell 字符串。"""
    parser = ChineseParser(
        description="将 XJar Plus SDK 安装到本机 Maven repository。",
        formatter_class=ChineseFormatter,
    )
    parser.add_argument("--maven-command", default="mvn", help="Maven 可执行命令或路径，默认 mvn。")
    return parser.parse_args()


def install_sdk(maven_command: str) -> int:
    """先安装 parent POM，再安装四个已经随 SDK 分发的模块及 sources JAR。"""
    executable = shutil.which(maven_command) or (str(Path(maven_command).resolve()) if Path(maven_command).is_file() else None)
    if executable is None:
        raise FileNotFoundError(f"找不到 Maven 命令：{maven_command}")
    sdk_root = Path(__file__).resolve().parent
    repository = sdk_root / "repository"
    parent = repository / f"xjar-plus-parent-{VERSION}.pom"
    if not parent.is_file():
        raise FileNotFoundError(f"SDK parent POM 不存在：{parent}")

    def stop_process_tree(process):
        """超时后只回收本次 Maven 启动的 Windows 子树或 Unix 进程组。"""
        if os.name == "nt":
            taskkill = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "taskkill.exe"
            try:
                subprocess.run(
                    [str(taskkill), "/PID", str(process.pid), "/T", "/F"],
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.DEVNULL,
                    timeout=10,
                    check=False,
                    creationflags=subprocess.CREATE_NO_WINDOW,
                    shell=False,
                )
            except (OSError, subprocess.TimeoutExpired):
                pass
            if process.poll() is None:
                process.kill()
        else:
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            for _ in range(20):
                try:
                    os.killpg(process.pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.1)
            else:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            if process.poll() is None:
                process.kill()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired as error:
            process.kill()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired as final_error:
                raise RuntimeError("无法在期限内回收 SDK 安装器启动的 Maven 进程。") from final_error
            raise RuntimeError("SDK 安装器启动的 Maven 未在清理宽限期内退出。") from error

    def invoke(arguments: list[str]) -> None:
        """逐个调用 install-file，超时回收进程树，失败时停止后续安装。"""
        process = subprocess.Popen(
            command_argv(executable, arguments),
            cwd=sdk_root,
            stdin=subprocess.DEVNULL,
            shell=windows_batch_command(executable),
            creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0,
            start_new_session=os.name != "nt",
        )
        try:
            return_code = process.wait(timeout=MAVEN_TIMEOUT_SECONDS)
        except KeyboardInterrupt:
            try:
                stop_process_tree(process)
            except BaseException:
                if process.poll() is None:
                    try:
                        process.kill()
                    except OSError:
                        pass
                    try:
                        process.wait(timeout=5)
                    except BaseException:
                        pass
            raise
        except subprocess.TimeoutExpired as error:
            stop_process_tree(process)
            raise TimeoutError(
                f"Maven install-file 执行超过 {MAVEN_TIMEOUT_SECONDS} 秒，已回收本次进程树。"
            ) from error
        if return_code != 0:
            raise RuntimeError(f"Maven install-file 执行失败，退出码：{return_code}")

    plugin = "org.apache.maven.plugins:maven-install-plugin:3.1.3:install-file"
    invoke([plugin, f"-Dfile={parent}", f"-DpomFile={parent}", "-Dpackaging=pom"])
    for artifact_id in MODULES:
        pom = repository / f"{artifact_id}-{VERSION}.pom"
        jar = repository / f"{artifact_id}-{VERSION}.jar"
        sources = repository / f"{artifact_id}-{VERSION}-sources.jar"
        for required in (pom, jar, sources):
            if not required.is_file():
                raise FileNotFoundError(f"SDK artifact 不存在：{required}")
        invoke([plugin, f"-Dfile={jar}", f"-DpomFile={pom}", f"-Dsources={sources}", "-Dpackaging=jar"])
    print(f"XJar Plus {VERSION} 库已安装到本机 Maven 仓库。")
    return 0


def main() -> int:
    """呈现安装失败原因并向调用方返回非零状态，便于脚本与 CI 可靠判断。"""
    args = parse_args()
    try:
        return install_sdk(args.maven_command)
    except (FileNotFoundError, OSError, RuntimeError, ValueError) as exc:
        print(f"SDK 安装失败：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
'''


class ReleaseError(RuntimeError):
    """表示发布输入、附件安全检查或事务校验失败。"""


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    """定义本地打包参数；默认输出目录固定在仓库 target 下且禁止覆盖。"""
    root = Path(__file__).resolve().parent.parent
    parser = ChineseArgumentParser(
        description="构建并本地校验 XJar Plus 发布附件；本命令不会创建或发布 GitHub Release。",
        formatter_class=ChineseHelpFormatter,
    )
    parser.add_argument("--version", default="2.0.0", help="发布版本或 v 前缀 tag，当前只接受 2.0.0。")
    parser.add_argument(
        "--output-directory", type=Path, default=root / "target/release/xjar-plus-2.0.0",
        help="全新输出目录；已存在时拒绝覆盖。",
    )
    parser.add_argument("--maven-command", default="mvn", help="Maven 可执行命令或路径，默认 mvn。")
    parser.add_argument("--skip-build", action="store_true", help="复用已构建产物，但仍校验 CLI 并生成全部附件。")
    parser.add_argument("--root", type=Path, default=root, help=argparse.SUPPRESS)
    return parser.parse_args(argv)


def read_project_identity(pom_path: Path) -> tuple[str, str]:
    """安全读取顶层 Maven groupId/version，不能由发布参数覆盖项目自身身份。"""
    raw = pom_path.read_bytes()
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        raise ReleaseError(f"拒绝含 DTD 或实体声明的 Maven POM：{pom_path}")
    try:
        root = ET.fromstring(raw)
    except ET.ParseError as exc:
        raise ReleaseError(f"Maven POM XML 无效：{exc}") from exc
    namespace = root.tag.partition("}")[0].lstrip("{") if "}" in root.tag else ""
    qname = (lambda name: f"{{{namespace}}}{name}") if namespace else (lambda name: name)
    return root.findtext(qname("groupId"), "").strip(), root.findtext(qname("version"), "").strip()


def validate_project_identity(root: Path, version: str) -> str:
    """只接受与源码 POM 完全一致的 2.0.0，避免打出错误版本名的合法-looking 附件。"""
    normalized = version.removeprefix("v")
    actual_group, actual_version = read_project_identity(root / "pom.xml")
    if normalized != "2.0.0" or actual_version != normalized:
        raise ReleaseError(f"发布 tag 与 POM 必须均为 2.0.0；请求为 {version!r}，POM 为 {actual_version!r}。")
    if actual_group != GROUP_ID:
        raise ReleaseError(f"Maven groupId 不符合预期：{actual_group!r}")
    return normalized


def copy_required(source: Path, destination: Path) -> None:
    """对每个声明的发布输入显式失败，不允许缺件时静默生成不完整 SDK。"""
    if not source.is_file():
        raise ReleaseError(f"缺少必需发布输入：{source}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, destination)


def sdk_readme(version: str) -> str:
    """生成与 SDK 四个 main 示例和 Python 本地安装器一致的自包含说明。"""
    return f'''<!-- @author kongweiguang -->
# XJar Plus {version} SDK

此压缩包包含四个 Java 库及其 POM、源码 JAR、可运行的 CLI JAR，以及 Java `main()` 示例。

## 运行 API 示例

请在当前目录使用 JDK 21 或更高版本，并按示例提供参数：

```text
java -cp xjar-plus-{version}.jar examples/EncryptMain.java app.jar encrypted.jar password.txt
java -cp xjar-plus-{version}.jar examples/IssuerMain.java demo demo.issuer
java -cp xjar-plus-{version}.jar examples/PackageMain.java app.jar dist password.txt demo.issuer demo windows-amd64
java -cp xjar-plus-{version}.jar examples/LicenseMain.java demo.issuer renewed-key.x 2027-12-31T16:00:00Z
```

四个示例及详细用法说明位于 `examples/`。上述路径只是占位符；用于真实应用前请先检查并替换。`demo.issuer` 是仅供发布者使用的秘密材料，不得发给客户或放入公开归档。

## 安装到本机 Maven 仓库

需要 Python 3.11+，并将 Maven 加入 `PATH`。Windows 通常运行 `python ./install_sdk.py`，macOS/Linux 通常运行 `python3 ./install_sdk.py`，无需 PowerShell。脚本先安装父 POM，再安装各库的 POM、二进制 JAR 和源码 JAR；不会发布或修改远程仓库。

安装后，在 Maven 项目中按需添加 groupId `io.github.kongweiguang.xjar.plus`、版本 `{version}` 的库模块。core 模块要求 Java 21；经典运行时和格式模块面向 Java 8；现代 Boot 适配器面向 Java 17。
'''


def create_sdk_archive(root: Path, sdk_root: Path, version: str, release_jar: Path) -> Path:
    """收集四个模块的 Maven 三件套、许可证与四份示例，生成仅含 SDK 内容的 ZIP。"""
    repository = sdk_root / "repository"
    example_root = sdk_root / "examples"
    repository.mkdir(parents=True)
    example_root.mkdir(parents=True)
    copy_required(root / "pom.xml", repository / f"xjar-plus-parent-{version}.pom")
    copy_required(root / "LICENSE", sdk_root / "LICENSE")
    copy_required(root / "NOTICE", sdk_root / "NOTICE")
    for module in SDK_MODULES:
        module_root = root / module
        for suffix in ("pom", "jar", "sources.jar"):
            source_name = f"{module}-{version}.{suffix}" if suffix == "pom" else (
                f"{module}-{version}-sources.jar" if suffix == "sources.jar" else f"{module}-{version}.jar"
            )
            copy_required(module_root / "target" / source_name if suffix != "pom" else module_root / "pom.xml",
                          repository / (source_name if suffix != "pom" else f"{module}-{version}.pom"))
    for filename in SDK_EXAMPLES:
        copy_required(root / "examples" / filename, example_root / filename)
    copy_required(release_jar, sdk_root / f"xjar-plus-{version}.jar")
    (sdk_root / "README.md").write_text(sdk_readme(version), encoding="utf-8", newline="\n")
    installer = SDK_INSTALLER_TEMPLATE.replace("__VERSION__", version)
    installer_path = sdk_root / "install_sdk.py"
    installer_path.write_text(installer, encoding="utf-8", newline="\n")
    installer_path.chmod(0o755)

    archive_path = sdk_root.parent / f"xjar-plus-{version}-sdk.zip"
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(sdk_root.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(sdk_root))
    return archive_path


def create_skill_archive(root: Path, stage: Path, version: str) -> Path:
    """按可安装 skill 的目录结构打包完整 skill 源，不把无关仓库内容带入附件。"""
    skill_root = root / ".agents/skills/xjar-plus"
    skill_file = skill_root / "SKILL.md"
    if not skill_file.is_file():
        raise ReleaseError(f"找不到可安装 skill 源：{skill_file}")
    archive_path = stage / f"xjar-plus-{version}-skill.zip"
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(skill_root.rglob("*")):
            if path.is_file():
                archive.write(path, Path(skill_root.name) / path.relative_to(skill_root))
    return archive_path


def assert_go_sbom_toolchain(go: str, launcher: Path) -> None:
    """明确区分 launcher Go 1.24 门槛与 CycloneDX 工具 Go 1.25 门槛，不隐式下载工具链。"""
    env = os.environ.copy()
    env["GOTOOLCHAIN"] = "local"
    result = run_checked(go, ["env", "GOVERSION"], cwd=launcher, env=env)
    match = re.fullmatch(r"go(\d+)\.(\d+)(?:\.\d+)?", result.stdout.strip())
    if match is None:
        raise ReleaseError(f"无法解析 Go SBOM 工具链版本：{result.stdout.strip()}")
    if (int(match.group(1)), int(match.group(2))) < (1, 25):
        raise ReleaseError(
            "生成 Go SBOM 需要 Go 1.25 或更高版本（cyclonedx-gomod v1.10.0）；"
            f"launcher 的测试门槛仍为 Go 1.24，当前检测到 {result.stdout.strip()}。"
        )


def assert_public_artifacts(stage: Path) -> None:
    """在哈希前检查公开文件名和压缩包路径，阻止密钥、客户包或 JDK 被带入资产。"""
    for path in sorted(stage.iterdir()):
        if not path.is_file():
            raise ReleaseError(f"发布暂存区出现非文件顶层项目：{path.name}")
        if FORBIDDEN_NAMES.fullmatch(path.name):
            raise ReleaseError(f"发布附件文件名命中敏感规则：{path.name}")
        if path.suffix.casefold() not in (".jar", ".zip"):
            continue
        try:
            with zipfile.ZipFile(path) as archive:
                unsafe = [name for name in archive.namelist() if FORBIDDEN_ARCHIVE_ENTRY.search(name)]
        except zipfile.BadZipFile as exc:
            raise ReleaseError(f"附件不是有效 JAR/ZIP：{path.name}") from exc
        if unsafe:
            raise ReleaseError(f"发布附件 {path.name} 内含禁止路径：{', '.join(unsafe)}")


def sha256(path: Path) -> str:
    """以固定大小分块计算哈希，避免把大 JAR 或 SBOM 一次性读入内存。"""
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_and_verify_hashes(stage: Path, version: str) -> None:
    """写入并重读七个公开内容附件的 SHA-256 清单，且验证最终附件集合固定为八项。"""
    expected_assets = {
        f"xjar-plus-{version}.jar",
        f"xjar-plus-{version}-compatibility-matrix.json",
        "COMPATIBILITY.md",
        f"xjar-plus-{version}-skill.zip",
        f"xjar-plus-{version}-sdk.zip",
        "xjar-plus-2.0.0-java.cdx.json",
        "xjar-plus-2.0.0-go.cdx.json",
    }
    actual_before_manifest = {path.name for path in stage.iterdir() if path.is_file()}
    if actual_before_manifest != expected_assets:
        raise ReleaseError(
            f"生成的发布附件集合不符；缺失={sorted(expected_assets - actual_before_manifest)}，"
            f"额外={sorted(actual_before_manifest - expected_assets)}"
        )
    lines = [f"{sha256(path)}  {path.name}" for path in sorted(stage.iterdir(), key=lambda item: item.name)
             if path.is_file()]
    manifest = stage / "SHA256SUMS"
    manifest.write_text("\n".join(lines) + "\n", encoding="ascii", newline="\n")
    manifest_text = manifest.read_text(encoding="ascii")
    if not manifest_text.endswith("\n"):
        raise ReleaseError("SHA256SUMS 缺少末尾换行，拒绝验收不完整清单。")
    entries: dict[str, str] = {}
    for line in manifest_text.splitlines():
        parts = line.split("  ", maxsplit=1)
        if len(parts) != 2 or re.fullmatch(r"[0-9a-f]{64}", parts[0]) is None:
            raise ReleaseError("SHA256SUMS 格式无效。")
        expected, filename = parts
        if Path(filename).name != filename or "\\" in filename or filename in entries:
            raise ReleaseError(f"SHA256SUMS 包含重复或非本地文件名：{filename!r}")
        entries[filename] = expected
    if set(entries) != expected_assets:
        raise ReleaseError(
            f"SHA256SUMS 文件集合不符；缺失={sorted(expected_assets - set(entries))}，"
            f"额外={sorted(set(entries) - expected_assets)}"
        )
    for filename, expected in entries.items():
        actual = sha256(stage / filename)
        if actual != expected:
            raise ReleaseError(f"发布附件 SHA-256 回读失败：{filename}")
    expected_final = expected_assets | {"SHA256SUMS"}
    actual_final = {path.name for path in stage.iterdir() if path.is_file()}
    if actual_final != expected_final:
        raise ReleaseError(f"最终发布目录文件集不符：{sorted(actual_final)}")


def build_assets(root: Path, version: str, output: Path, maven_command: str, skip_build: bool) -> Path:
    """执行完整本地发布闭环：身份校验、暂存、SBOM、安全扫描、哈希回读和原子目录提升。"""
    normalized_version = validate_project_identity(root, version)
    maven = resolve_executable(maven_command)
    release_jar = root / f"xjar-plus-app/target/xjar-plus-{normalized_version}.jar"
    if not skip_build:
        run_checked(maven, ["-B", "-ntp", "-Pquality", "-Pintegration", "verify"], cwd=root, stream=True)
    if not release_jar.is_file():
        raise ReleaseError(f"缺少可执行 CLI JAR：{release_jar}")
    java = resolve_executable("java")
    run_checked(java, ["-jar", str(release_jar), "--help"], cwd=root)

    output_path = output.expanduser().absolute()
    if output_path.exists() or output_path.is_symlink():
        raise ReleaseError(f"发布输出目录已存在；为避免覆盖用户数据，请指定全新目录：{output_path}")
    output_parent = output_path.parent
    output_parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".release-stage-", dir=output_parent)).resolve()
    if stage.parent != output_parent.resolve():
        shutil.rmtree(stage)
        raise ReleaseError(f"拒绝在输出目录父级之外创建发布暂存：{stage}")
    try:
        copy_required(release_jar, stage / f"xjar-plus-{normalized_version}.jar")
        copy_required(root / "docs/compatibility-matrix.json", stage / f"xjar-plus-{normalized_version}-compatibility-matrix.json")
        (stage / "COMPATIBILITY.md").write_text(COMPATIBILITY_NOTE, encoding="utf-8", newline="\n")
        create_skill_archive(root, stage, normalized_version)
        sdk_work = stage / ".sdk-work"
        sdk_work.mkdir()
        sdk_archive = create_sdk_archive(root, sdk_work, normalized_version, release_jar)
        expected_sdk_archive = stage / f"xjar-plus-{normalized_version}-sdk.zip"
        if sdk_archive != expected_sdk_archive:
            sdk_archive.rename(expected_sdk_archive)
        shutil.rmtree(sdk_work)

        java_sbom_path = stage / "xjar-plus-2.0.0-java.cdx.json"
        run_checked(
            maven,
            ["-B", "-ntp", "org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom",
             f"-DoutputDirectory={stage}", "-DoutputName=xjar-plus-2.0.0-java.cdx",
             "-DoutputFormat=json", "-DincludeTestScope=false"],
            cwd=root,
            stream=True,
        )
        if not java_sbom_path.is_file():
            raise ReleaseError("Java SBOM 生成器未产生声明的输出文件。")
        json.loads(java_sbom_path.read_text(encoding="utf-8"))

        go = resolve_executable("go")
        launcher = root / "launcher"
        assert_go_sbom_toolchain(go, launcher)
        env = os.environ.copy()
        env["GOTOOLCHAIN"] = "local"
        go_sbom_path = stage / "xjar-plus-2.0.0-go.cdx.json"
        run_checked(
            go,
            ["run", "github.com/CycloneDX/cyclonedx-gomod/cmd/cyclonedx-gomod@v1.10.0",
             "mod", "-licenses", "-type", "application", "-json", "-output", str(go_sbom_path), "."],
            cwd=launcher,
            env=env,
            stream=True,
        )
        if not go_sbom_path.is_file():
            raise ReleaseError("Go SBOM 生成器未产生声明的输出文件。")
        json.loads(go_sbom_path.read_text(encoding="utf-8"))
        assert_public_artifacts(stage)
        write_and_verify_hashes(stage, normalized_version)
        if output_path.exists() or output_path.is_symlink():
            raise ReleaseError(f"输出目录在打包期间出现；拒绝覆盖：{output_path}")
        os.rename(stage, output_path)
        print(f"本地发布附件已完成并通过回读校验：{output_path}")
        return output_path
    finally:
        if stage.exists() and stage.name.startswith(".release-stage-") and stage.parent == output_parent.resolve():
            shutil.rmtree(stage)


def main(argv: Sequence[str] | None = None) -> int:
    """汇总本地执行异常为非零返回，保留失败线索且不执行任何远程发布操作。"""
    args = parse_args(argv)
    try:
        build_assets(args.root.resolve(), args.version, args.output_directory, args.maven_command, args.skip_build)
    except (ET.ParseError, FileNotFoundError, OSError, ReleaseError, RuntimeError, ValueError) as exc:
        print(f"本地发布打包失败：{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
