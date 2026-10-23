/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.domain.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * 用于签发许可证的领域命令，不通过展示层 API 暴露发行密钥。
 */
public final class LicenseIssueCommand {
    private final Path issuerFile;
    private final Path output;
    private final Instant notBefore;
    private final Instant notAfter;

    /**
     * 在加载凭据前冻结文件系统和时间参数。
     */
    public LicenseIssueCommand(Path issuerFile, Path output, Instant notBefore, Instant notAfter) {
        this.issuerFile = Objects.requireNonNull(issuerFile, "issuerFile");
        this.output = Objects.requireNonNull(output, "output");
        this.notBefore = Objects.requireNonNull(notBefore, "notBefore");
        this.notAfter = notAfter;
    }

    /**
     * 返回私有发行凭据路径。
     */
    public Path issuerFile() {
        return issuerFile;
    }

    /**
     * 返回新许可证的输出路径。
     */
    public Path output() {
        return output;
    }

    /**
     * 返回包含在有效期内的生效时刻。
     */
    public Instant notBefore() {
        return notBefore;
    }

    /**
     * 返回不包含在有效期内的失效时刻；null 表示永久有效。
     */
    public Instant notAfter() {
        return notAfter;
    }
}
