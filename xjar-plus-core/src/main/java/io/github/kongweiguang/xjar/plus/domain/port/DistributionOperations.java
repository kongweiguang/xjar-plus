/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.domain.port;

import io.github.kongweiguang.xjar.plus.domain.model.BuildArtifact;
import io.github.kongweiguang.xjar.plus.domain.model.LauncherCommand;
import io.github.kongweiguang.xjar.plus.domain.model.LicenseIssueCommand;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Go 启动器和发行凭据端口，使归档规则不依赖进程或文件系统细节。
 */
public interface DistributionOperations {
    /**
     * 为稳定的应用标识创建新的发行凭据。
     */
    BuildArtifact createIssuer(Path output, String appId) throws IOException;

    /**
     * 使用已有的私有凭据签发许可证。
     */
    BuildArtifact issueLicense(LicenseIssueCommand command) throws IOException;

    /**
     * 构建并原子提交指定目标平台的 Go 启动器发行包。
     */
    BuildArtifact packageLauncher(LauncherCommand command) throws IOException;
}
