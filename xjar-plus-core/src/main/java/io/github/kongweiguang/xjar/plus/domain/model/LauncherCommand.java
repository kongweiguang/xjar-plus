/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.domain.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 基于共用归档策略生成授权启动器发行包的领域命令。
 */
public final class LauncherCommand {
    private final ArchiveCommand archive;
    private final BuildSettings settings;
    private final LicenseTerm licenseTerm;

    /**
     * 冻结构建和许可证参数，避免打包过程中观察到调用方的并发修改。
     */
    public LauncherCommand(ArchiveCommand archive, BuildSettings settings, LicenseTerm licenseTerm) {
        this.archive = Objects.requireNonNull(archive, "archive");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.licenseTerm = Objects.requireNonNull(licenseTerm, "licenseTerm");
    }

    /**
     * 返回与独立加密共用的归档策略。
     */
    public ArchiveCommand archive() {
        return archive;
    }

    /**
     * 返回发行包的父目录。
     */
    public Path output() {
        return settings.output();
    }

    /**
     * 返回显式交叉编译目标标识。
     */
    public String target() {
        return settings.target();
    }

    /**
     * 返回私有发行凭据路径。
     */
    public Path issuerFile() {
        return settings.issuerFile();
    }

    /**
     * 返回用于绑定所有构建和续期的 appId。
     */
    public String appId() {
        return settings.appId();
    }

    /**
     * 返回可选的目标 JDK 归档。
     */
    public Path jdkZip() {
        return settings.jdkZip();
    }

    /**
     * 返回显式 Go 工具路径；null 表示从 PATH 查找。
     */
    public Path goExecutable() {
        return settings.goExecutable();
    }

    /**
     * 返回 JVM argv 参数。
     */
    public List<String> jvmArgs() {
        return settings.jvmArgs();
    }

    /**
     * 返回应用 argv 参数。
     */
    public List<String> appArgs() {
        return settings.appArgs();
    }

    /**
     * 返回许可证开始生效的时刻。
     */
    public Instant notBefore() {
        return licenseTerm.notBefore();
    }

    /**
     * 返回许可证失效的时刻；null 表示永久有效。
     */
    public Instant notAfter() {
        return licenseTerm.notAfter();
    }

    /**
     * 集中保存目标与输出参数，同时将许可证时间校验保持为独立值对象。
     */
    public record BuildSettings(Path output, String target, Path issuerFile, String appId, Path jdkZip,
                                Path goExecutable, List<String> jvmArgs, List<String> appArgs) {
        /**
         * 复制参数列表，避免异步打包读取到调用方后续修改的内容。
         */
        public BuildSettings {
            Objects.requireNonNull(output, "output");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(issuerFile, "issuerFile");
            Objects.requireNonNull(appId, "appId");
            jvmArgs = List.copyOf(jvmArgs);
            appArgs = List.copyOf(appArgs);
        }
    }

    /**
     * 表示单个许可证有效期，使时间边界作为完整领域值统一校验。
     */
    public record LicenseTerm(Instant notBefore, Instant notAfter) {
        /**
         * 在值对象边界拒绝倒置的有效期，避免错误参数进入构建流程。
         */
        public LicenseTerm {
            Objects.requireNonNull(notBefore, "notBefore");
            if (notAfter != null && notAfter.isBefore(notBefore)) {
                throw new IllegalArgumentException("notAfter must not be before notBefore");
            }
        }
    }
}
