/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.api;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 不可变的启动器构建参数；授权和进程专属产物均由打包流程负责。
 */
public final class LauncherRequest implements AutoCloseable {
    private static final Set<String> TARGETS = Set.of(
            "windows-amd64", "windows-arm64", "linux-amd64", "linux-arm64", "darwin-amd64", "darwin-arm64");

    private final EncryptionRequest encryption;
    private final Path output;
    private final String target;
    private final Path issuerFile;
    private final String appId;
    private final Path jdkZip;
    private final Path goExecutable;
    private final List<String> jvmArgs;
    private final List<String> appArgs;
    private final Instant notBefore;
    private final Instant notAfter;

    /**
     * 冻结打包参数，并在产生副作用前拒绝不支持的目标平台或倒置的有效期。
     */
    private LauncherRequest(Builder builder) {
        this.encryption = Objects.requireNonNull(builder.encryption, "encryption");
        this.output = Objects.requireNonNull(builder.output, "output");
        this.target = Objects.requireNonNull(builder.target, "target");
        this.issuerFile = Objects.requireNonNull(builder.issuerFile, "issuerFile");
        this.appId = Objects.requireNonNull(builder.appId, "appId");
        this.jdkZip = builder.jdkZip;
        this.goExecutable = builder.goExecutable;
        this.jvmArgs = List.copyOf(builder.jvmArgs);
        this.appArgs = List.copyOf(builder.appArgs);
        this.notBefore = Objects.requireNonNull(builder.notBefore, "notBefore");
        this.notAfter = builder.notAfter;
        if (!TARGETS.contains(target)) {
            throw new IllegalArgumentException("Unsupported target: " + target);
        }
        if (notAfter != null && notAfter.isBefore(notBefore)) {
            throw new IllegalArgumentException("notAfter must not be before notBefore");
        }
    }

    /**
     * 创建以当前时刻为生效边界的不可变请求构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回共用归档参数；输出路径由打包事务单独管理。
     */
    public EncryptionRequest encryption() {
        return encryption;
    }

    /**
     * 返回最终写入 appId 发行目录的父目录。
     */
    public Path output() {
        return output;
    }

    /**
     * 返回显式 GOOS/GOARCH 目标标识，避免依赖宿主默认值而生成意外平台的程序。
     */
    public String target() {
        return target;
    }

    /**
     * 返回仅打包时需要的私有发行凭据路径。
     */
    public Path issuerFile() {
        return issuerFile;
    }

    /**
     * 返回写入配置和许可证文件的稳定授权绑定标识。
     */
    public String appId() {
        return appId;
    }

    /**
     * 返回可选的目标 JDK 归档；null 表示目标机器必须自行提供 Java。
     */
    public Path jdkZip() {
        return jdkZip;
    }

    /**
     * 返回可选的 Go 工具路径；null 表示按约定从 PATH 查找。
     */
    public Path goExecutable() {
        return goExecutable;
    }

    /**
     * 返回不可变 JVM 参数列表，每个元素均作为独立 argv 值而非 shell 文本处理。
     */
    public List<String> jvmArgs() {
        return jvmArgs;
    }

    /**
     * 返回不可变应用参数列表，不进行 shell 插值。
     */
    public List<String> appArgs() {
        return appArgs;
    }

    /**
     * 返回许可证开始生效的时刻，该边界包含在有效期内。
     */
    public Instant notBefore() {
        return notBefore;
    }

    /**
     * 返回许可证失效时刻，该边界不包含在有效期内；null 表示永久有效。
     */
    public Instant notAfter() {
        return notAfter;
    }

    /**
     * 关闭并清理请求持有的加密参数，使启动器调用方可通过 try-with-resources 统一释放凭据。
     */
    @Override
    public void close() {
        encryption.close();
    }

    /**
     * 收集构建选择，随后由打包器校验路径并在暂存区构建产物。
     */
    public static final class Builder {
        private final List<String> jvmArgs = new ArrayList<>();
        private final List<String> appArgs = new ArrayList<>();
        private EncryptionRequest encryption;
        private Path output;
        private String target = "windows-amd64";
        private Path issuerFile;
        private String appId;
        private Path jdkZip;
        private Path goExecutable;
        private Instant notBefore = Instant.now();
        private Instant notAfter;

        /**
         * 集中管理目标平台和生效时间默认值，使 API 与 CLI 遵循相同的初始策略。
         */
        private Builder() {
        }

        /**
         * 复用独立加密的归档策略，避免不同入口生成不同内容。
         */
        public Builder encryption(EncryptionRequest value) {
            this.encryption = Objects.requireNonNull(value, "encryption");
            return this;
        }

        /**
         * 指定输出父目录；服务会在其下以原子方式提交一个 appId 发行目录。
         */
        public Builder output(Path value) {
            this.output = Objects.requireNonNull(value, "output");
            return this;
        }

        /**
         * 要求显式指定受支持的目标平台，避免交叉编译意外跟随宿主系统。
         */
        public Builder target(String value) {
            this.target = Objects.requireNonNull(value, "target");
            return this;
        }

        /**
         * 指定本地发行凭据；该文件不会复制到公共发布件或最终客户包中。
         */
        public Builder issuerFile(Path value) {
            this.issuerFile = Objects.requireNonNull(value, "issuerFile");
            return this;
        }

        /**
         * 将重复构建和续期许可证绑定到同一稳定应用标识。
         */
        public Builder appId(String value) {
            this.appId = value;
            return this;
        }

        /**
         * 可选地附带 JDK；构建前会按所选目标平台校验其归档布局。
         */
        public Builder jdkZip(Path value) {
            this.jdkZip = value;
            return this;
        }

        /**
         * 显式指定 Go 工具路径，便于离线或 CI 构建稳定地选择编译器。
         */
        public Builder goExecutable(Path value) {
            this.goExecutable = value;
            return this;
        }

        /**
         * 将一个 JVM 参数作为单独值追加，确保空格仍是参数内容而非 shell 语法。
         */
        public Builder jvmArg(String value) {
            this.jvmArgs.add(Objects.requireNonNull(value, "jvmArg"));
            return this;
        }

        /**
         * 将一个应用参数作为单独值追加，保留进程 argv 的原始边界。
         */
        public Builder appArg(String value) {
            this.appArgs.add(Objects.requireNonNull(value, "appArg"));
            return this;
        }

        /**
         * 设置包含在有效期内的生效边界；默认采用构建器创建时刻以简化本地使用。
         */
        public Builder notBefore(Instant value) {
            this.notBefore = Objects.requireNonNull(value, "notBefore");
            return this;
        }

        /**
         * 设置不包含在有效期内的失效边界；null 明确表示许可证永久有效。
         */
        public Builder notAfter(Instant value) {
            this.notAfter = value;
            return this;
        }

        /**
         * 在任何文件系统或进程副作用开始前冻结请求参数。
         */
        public LauncherRequest build() {
            return new LauncherRequest(this);
        }
    }

}
