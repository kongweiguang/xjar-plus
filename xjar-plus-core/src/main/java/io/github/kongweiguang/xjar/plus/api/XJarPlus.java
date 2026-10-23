/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.api;

import io.github.kongweiguang.xjar.plus.application.CoreApplication;
import io.github.kongweiguang.xjar.plus.domain.port.ArchiveOperations;
import io.github.kongweiguang.xjar.plus.infrastructure.GoDistributionOperations;
import io.github.kongweiguang.xjar.plus.infrastructure.JarArchiveOperations;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 静态门面负责隔离用户代码与 DDD 应用层、基础设施类型。
 */
public final class XJarPlus {
    private static final ArchiveOperations ARCHIVES = new JarArchiveOperations();
    private static final CoreApplication APPLICATION = new CoreApplication(
            ARCHIVES, new GoDistributionOperations(ARCHIVES));

    /**
     * 门面只维护一套无状态应用组合，因此禁止外部实例化。
     */
    private XJarPlus() {
    }

    /**
     * 只检查归档结构而不解密条目，便于调用方安全选择后续操作。
     */
    public static ArchiveInfo inspect(Path input) throws IOException {
        return APPLICATION.inspect(input);
    }

    /**
     * 将加密结果写入新路径，并交由共用归档用例管理事务。
     */
    public static BuildResult encrypt(EncryptionRequest request) throws IOException {
        return APPLICATION.encrypt(request);
    }

    /**
     * 恢复加密归档到新路径，并认证每个受保护条目。
     */
    public static BuildResult decrypt(EncryptionRequest request) throws IOException {
        return APPLICATION.decrypt(request);
    }

    /**
     * 认证密钥信封、索引和受保护条目，但不写出恢复后的文件。
     */
    public static ArchiveInfo verify(Path input, char[] password) throws IOException {
        return APPLICATION.verify(input, password);
    }

    /**
     * 通过单次暂存事务构建带授权的 Go 启动器，并返回可执行文件路径。
     */
    public static BuildResult packageLauncher(LauncherRequest request) throws IOException {
        return APPLICATION.packageLauncher(request);
    }

    /**
     * 在指定私有路径创建 XJI2 发行凭据；若文件已存在则拒绝覆盖。
     */
    public static BuildResult createIssuer(Path output, String appId) throws IOException {
        return APPLICATION.createIssuer(output, appId);
    }

    /**
     * 签发绑定发行凭据 appId 的 XJL2 许可证，并为其单独生成认证随机数。
     */
    public static BuildResult issueLicense(LicenseRequest request) throws IOException {
        return APPLICATION.issueLicense(request);
    }
}
