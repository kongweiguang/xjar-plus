/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * 不可变的许可证签发请求，仅包含路径和时间，不保存密钥等秘密材料。
 */
public final class LicenseRequest {
    private final Path issuerFile;
    private final Path output;
    private final Instant notBefore;
    private final Instant notAfter;

    /**
     * 冻结签发参数，并在读取发行凭据前拒绝起止时间倒置的有效期。
     */
    private LicenseRequest(Builder builder) {
        this.issuerFile = Objects.requireNonNull(builder.issuerFile, "issuerFile");
        this.output = Objects.requireNonNull(builder.output, "output");
        this.notBefore = Objects.requireNonNull(builder.notBefore, "notBefore");
        this.notAfter = builder.notAfter;
        if (notAfter != null && notAfter.isBefore(notBefore)) {
            throw new IllegalArgumentException("notAfter must not be before notBefore");
        }
    }

    /**
     * 创建以当前时刻为生效边界的构建器，简化常见续期流程。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回发行凭据文件位置，但不将文件内容暴露给公开请求诊断。
     */
    public Path issuerFile() {
        return issuerFile;
    }

    /**
     * 返回许可证目标路径；用例拒绝覆盖已有文件以保护现有许可证。
     */
    public Path output() {
        return output;
    }

    /**
     * 返回许可证开始生效的时刻，该边界包含在有效期内。
     */
    public Instant notBefore() {
        return notBefore;
    }

    /**
     * 返回许可证失效时刻，该边界不包含在有效期内；null 表示永不过期。
     */
    public Instant notAfter() {
        return notAfter;
    }

    /**
     * 收集签发参数，但不在 API 对象中保存密钥字节。
     */
    public static final class Builder {
        private Path issuerFile;
        private Path output;
        private Instant notBefore = Instant.now();
        private Instant notAfter;

        /**
         * 集中设置以当前时刻为生效时间的默认值，使省略日期仍是有效的签发请求。
         */
        private Builder() {
        }

        /**
         * 指定用于认证新许可证的私有发行凭据。
         */
        public Builder issuerFile(Path value) {
            this.issuerFile = Objects.requireNonNull(value, "issuerFile");
            return this;
        }

        /**
         * 指定新输出文件；应用服务会拒绝覆盖已有文件。
         */
        public Builder output(Path value) {
            this.output = Objects.requireNonNull(value, "output");
            return this;
        }

        /**
         * 设置延迟签发时使用的生效边界，许可证在该时刻开始有效。
         */
        public Builder notBefore(Instant value) {
            this.notBefore = Objects.requireNonNull(value, "notBefore");
            return this;
        }

        /**
         * 设置失效边界；null 表示许可证永久有效。
         */
        public Builder notAfter(Instant value) {
            this.notAfter = value;
            return this;
        }

        /**
         * 在读取发行凭据前冻结请求状态。
         */
        public LicenseRequest build() {
            return new LicenseRequest(this);
        }
    }
}
