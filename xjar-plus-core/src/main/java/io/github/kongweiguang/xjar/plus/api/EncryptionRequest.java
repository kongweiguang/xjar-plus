/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 供独立加密和启动器打包共用的不可变请求。
 */
public final class EncryptionRequest implements AutoCloseable {
    private final Path input;
    private final Path output;
    private final char[] password;
    private final List<String> includes;
    private final List<String> excludes;
    private final boolean encryptDependencies;
    private final ProgressListener progress;
    private final CancellationToken cancellation;
    private boolean closed;

    /**
     * 复制构建器中的秘密，使构建器清理自身状态时不会影响请求持有的凭据。
     */
    private EncryptionRequest(Builder builder) {
        this.input = Objects.requireNonNull(builder.input, "input");
        this.output = builder.output;
        this.password = Objects.requireNonNull(builder.password, "password").clone();
        this.includes = List.copyOf(builder.includes);
        this.excludes = List.copyOf(builder.excludes);
        this.encryptDependencies = builder.encryptDependencies;
        this.progress = builder.progress;
        this.cancellation = builder.cancellation;
    }

    /**
     * 创建请求构建器，使同一不可变请求可安全地跨 UI、CLI 和 API 边界传递。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回源归档路径，输出路径校验由具体操作负责。
     */
    public Path input() {
        return input;
    }

    /**
     * 返回可选目标路径；启动器打包会在提交前自行持有私有暂存目标。
     */
    public Path output() {
        return output;
    }

    /**
     * 返回秘密字符数组的防御性副本，调用方可在调用后清除自己的密码缓冲区。
     */
    public char[] password() {
        if (closed) {
            throw new IllegalStateException("Encryption request is closed");
        }
        return password.clone();
    }

    /**
     * 调用方使用完请求后清除请求持有的密码副本，缩短秘密在内存中的存留时间。
     */
    @Override
    public void close() {
        if (!closed) {
            java.util.Arrays.fill(password, '\0');
            closed = true;
        }
    }

    /**
     * 按声明顺序返回不可变的包含规则，保证归档处理结果可预测。
     */
    public List<String> includes() {
        return includes;
    }

    /**
     * 按声明顺序返回不可变的排除规则，保证排除策略一致。
     */
    public List<String> excludes() {
        return excludes;
    }

    /**
     * 返回是否将嵌套依赖归档纳入保护范围。
     */
    public boolean encryptDependencies() {
        return encryptDependencies;
    }

    /**
     * 返回非空进度监听器，避免操作实现中出现空值分支。
     */
    public ProgressListener progress() {
        return progress;
    }

    /**
     * 返回供该请求启动的全部工作共用的非空取消信号。
     */
    public CancellationToken cancellation() {
        return cancellation;
    }

    /**
     * 收集调用方可变参数，并在构建时冻结为不可变请求。
     */
    public static final class Builder {
        private final List<String> includes = new ArrayList<>();
        private final List<String> excludes = new ArrayList<>();
        private Path input;
        private Path output;
        private char[] password;
        private boolean encryptDependencies;
        private ProgressListener progress = (stage, percent) -> {
        };
        private CancellationToken cancellation = CancellationToken.NONE;
        private boolean built;

        /**
         * 集中设置安全的空操作默认值，等待调用方补齐必需的归档路径和密码。
         */
        private Builder() {
        }

        /**
         * 提前拒绝空白规则，避免批处理时因规则未匹配而难以诊断。
         */
        private static String requirePattern(String value) {
            final String pattern = Objects.requireNonNull(value, "pattern");
            if (pattern.isBlank()) {
                throw new IllegalArgumentException("Pattern must not be blank");
            }
            return pattern;
        }

        /**
         * 保存路径时不解析符号链接，使应用层能按用户输入比较并报告路径。
         */
        public Builder input(Path value) {
            ensureOpen();
            this.input = Objects.requireNonNull(value, "input");
            return this;
        }

        /**
         * 允许省略输出路径，因为 packageLauncher 会在私有暂存区生成加密归档。
         */
        public Builder output(Path value) {
            ensureOpen();
            this.output = value;
            return this;
        }

        /**
         * 立即复制传入的秘密，避免调用方后续修改数组影响正在构建的请求。
         */
        public Builder password(char[] value) {
            ensureOpen();
            clearPassword();
            this.password = Objects.requireNonNull(value, "password").clone();
            return this;
        }

        /**
         * 追加一条包含 glob，使 CLI 重复参数与构建器调用具有相同语义。
         */
        public Builder include(String value) {
            ensureOpen();
            this.includes.add(requirePattern(value));
            return this;
        }

        /**
         * 追加一条排除 glob，匹配语义统一交由归档策略处理。
         */
        public Builder exclude(String value) {
            ensureOpen();
            this.excludes.add(requirePattern(value));
            return this;
        }

        /**
         * 显式选择是否保护嵌套依赖，因为重写内嵌归档会改变其字节内容。
         */
        public Builder encryptDependencies(boolean value) {
            ensureOpen();
            this.encryptDependencies = value;
            return this;
        }

        /**
         * 替换默认空操作监听器，同时避免请求依赖具体 UI 框架类型。
         */
        public Builder progress(ProgressListener value) {
            ensureOpen();
            this.progress = Objects.requireNonNull(value, "progress");
            return this;
        }

        /**
         * 替换默认取消信号，使任务能在安全写入边界协作式终止。
         */
        public Builder cancellation(CancellationToken value) {
            ensureOpen();
            this.cancellation = Objects.requireNonNull(value, "cancellation");
            return this;
        }

        /**
         * 冻结已收集的参数；具体操作要求由门面边界校验。
         */
        public EncryptionRequest build() {
            ensureOpen();
            try {
                final EncryptionRequest request = new EncryptionRequest(this);
                built = true;
                return request;
            } finally {
                clearPassword();
            }
        }

        /**
         * 构建器密码副本清零后禁止再次使用，避免产生含糊的重用行为。
         */
        private void ensureOpen() {
            if (built) {
                throw new IllegalStateException("Encryption request builder can only build once");
            }
        }

        /**
         * 只清除构建器持有的副本，不修改调用方传入的字符数组。
         */
        private void clearPassword() {
            if (password != null) {
                java.util.Arrays.fill(password, '\0');
                password = null;
            }
        }
    }
}
