/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.application;

import io.github.kongweiguang.xjar.plus.api.ArchiveInfo;
import io.github.kongweiguang.xjar.plus.api.BuildResult;
import io.github.kongweiguang.xjar.plus.api.CancellationToken;
import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.LauncherRequest;
import io.github.kongweiguang.xjar.plus.api.LicenseRequest;
import io.github.kongweiguang.xjar.plus.api.ProgressListener;
import io.github.kongweiguang.xjar.plus.domain.model.ArchiveCommand;
import io.github.kongweiguang.xjar.plus.domain.model.ArchiveSummary;
import io.github.kongweiguang.xjar.plus.domain.model.BuildArtifact;
import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import io.github.kongweiguang.xjar.plus.domain.model.LauncherCommand;
import io.github.kongweiguang.xjar.plus.domain.model.LicenseIssueCommand;
import io.github.kongweiguang.xjar.plus.domain.model.ProgressReporter;
import io.github.kongweiguang.xjar.plus.domain.port.ArchiveOperations;
import io.github.kongweiguang.xjar.plus.domain.port.DistributionOperations;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 通过注入的领域端口编排公开请求，同时保持基础设施实现可替换。
 */
public final class CoreApplication {
    private final ArchiveOperations archives;
    private final DistributionOperations distributions;

    /**
     * 使用注入端口，使核心流程可在不启动 Go 或依赖 ZIP 实现的情况下测试。
     */
    public CoreApplication(ArchiveOperations archives, DistributionOperations distributions) {
        this.archives = Objects.requireNonNull(archives, "archives");
        this.distributions = Objects.requireNonNull(distributions, "distributions");
    }

    /**
     * 将请求回调映射到领域端口，使应用策略不依赖 API 包。
     */
    private static ArchiveCommand toDomain(EncryptionRequest request, Path output) {
        Objects.requireNonNull(request, "request");
        final ProgressListener progress = request.progress();
        final CancellationToken cancellation = request.cancellation();
        final ProgressReporter reporter = progress::onProgress;
        final CancellationProbe probe = cancellation::isCancelled;
        final char[] password = request.password();
        try {
            return new ArchiveCommand(request.input(), output, password, request.includes(), request.excludes(),
                    request.encryptDependencies(), reporter, probe);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    /**
     * 在适配器创建暂存文件前拒绝缺少输出路径的直接写入操作。
     */
    private static void requireOutput(EncryptionRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.output() == null) {
            throw new IllegalArgumentException("output is required for direct archive operations");
        }
    }

    /**
     * 解密承诺恢复完整原始归档，因此必须拒绝会被静默忽略的加密筛选参数。
     */
    private static void requireFullRestorePolicy(EncryptionRequest request) {
        Objects.requireNonNull(request, "request");
        if (!request.includes().isEmpty() || !request.excludes().isEmpty() || request.encryptDependencies()) {
            throw new IllegalArgumentException(
                    "decrypt restores the complete original archive; include, exclude, and encryptDependencies "
                    + "are only valid for encryption or packaging");
        }
    }

    /**
     * 集中转换操作结果，确保所有入口暴露相同的不可变输出值。
     */
    private static BuildResult toApi(BuildArtifact result) {
        return new BuildResult(result.output(), result.artifactId(), result.warnings());
    }

    /**
     * 集中转换归档信息，确保 CLI、UI 和 Java API 使用相同的家族名称。
     */
    private static ArchiveInfo toApi(ArchiveSummary summary) {
        return new ArchiveInfo(summary.kind(), summary.mainClass(), summary.encrypted(),
                summary.entries(), summary.minimumJava());
    }

    /**
     * 将已校验的归档事实转换为稳定的公开摘要。
     */
    public ArchiveInfo inspect(Path input) throws IOException {
        return toApi(archives.inspect(input));
    }

    /**
     * 委派前要求明确目标路径，避免直接构建失败时静默选择其他位置。
     */
    public BuildResult encrypt(EncryptionRequest request) throws IOException {
        requireOutput(request);
        try (ArchiveCommand command = toDomain(request, request.output())) {
            return toApi(archives.encrypt(command));
        }
    }

    /**
     * 恢复全部原始条目，并在访问归档前拒绝仅适用于加密的筛选参数。
     */
    public BuildResult decrypt(EncryptionRequest request) throws IOException {
        requireFullRestorePolicy(request);
        requireOutput(request);
        try (ArchiveCommand command = toDomain(request, request.output())) {
            return toApi(archives.decrypt(command));
        }
    }

    /**
     * 复用请求策略但覆盖输出设置，确保验证操作绝不写出恢复后的明文。
     */
    public ArchiveInfo verify(Path input, char[] password) throws IOException {
        try (ArchiveCommand command = new ArchiveCommand(input, null, password,
                java.util.List.of(), java.util.List.of(), false, (stage, percent) -> {
        }, CancellationProbe.NEVER)) {
            return toApi(archives.verify(command));
        }
    }

    /**
     * 将构建器已冻结归档策略的启动器请求映射为领域命令。
     */
    public BuildResult packageLauncher(LauncherRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        try (ArchiveCommand archive = toDomain(request.encryption(), null)) {
            final LauncherCommand.BuildSettings settings = new LauncherCommand.BuildSettings(request.output(),
                    request.target(), request.issuerFile(), request.appId(), request.jdkZip(),
                    request.goExecutable(), request.jvmArgs(), request.appArgs());
            final LauncherCommand command = new LauncherCommand(archive, settings,
                    new LauncherCommand.LicenseTerm(request.notBefore(), request.notAfter()));
            return toApi(distributions.packageLauncher(command));
        }
    }

    /**
     * 委托发行凭据创建，因为私钥格式由分发边界负责管理。
     */
    public BuildResult createIssuer(Path output, String appId) throws IOException {
        return toApi(distributions.createIssuer(output, appId));
    }

    /**
     * 将许可证时间映射到领域命令，不向 API 调用方暴露凭据字节。
     */
    public BuildResult issueLicense(LicenseRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        final LicenseIssueCommand command = new LicenseIssueCommand(request.issuerFile(), request.output(),
                request.notBefore(), request.notAfter());
        return toApi(distributions.issueLicense(command));
    }
}
