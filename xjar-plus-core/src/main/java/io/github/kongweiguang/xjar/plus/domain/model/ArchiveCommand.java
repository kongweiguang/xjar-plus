/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.domain.model;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 承载加密、解密和验证所需最小归档策略的领域命令。
 */
public final class ArchiveCommand implements AutoCloseable {
    private final Path input;
    private final Path output;
    private final char[] password;
    private final List<String> includes;
    private final List<String> excludes;
    private final boolean encryptDependencies;
    private final ProgressReporter progress;
    private final CancellationProbe cancellation;
    private boolean closed;

    /**
     * 复制秘密和集合，因为同一请求可能同时用于直接操作和启动器打包。
     */
    public ArchiveCommand(Path input, Path output, char[] password, List<String> includes, List<String> excludes,
                          boolean encryptDependencies, ProgressReporter progress, CancellationProbe cancellation) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = output;
        this.password = Objects.requireNonNull(password, "password").clone();
        this.includes = List.copyOf(includes);
        this.excludes = List.copyOf(excludes);
        this.encryptDependencies = encryptDependencies;
        this.progress = Objects.requireNonNull(progress, "progress");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    /**
     * 返回用例处理的源文件路径。
     */
    public Path input() {
        return input;
    }

    /**
     * 返回可选输出路径；启动器构建会由适配器提供私有暂存路径。
     */
    public Path output() {
        return output;
    }

    /**
     * 返回密码防御性副本，避免适配器清理时修改调用方持有的数据。
     */
    public char[] password() {
        if (closed) {
            throw new IllegalStateException("Archive command is closed");
        }
        return password.clone();
    }

    /**
     * 应用用例结束后清除命令持有的秘密副本。
     */
    @Override
    public void close() {
        if (!closed) {
            java.util.Arrays.fill(password, '\0');
            closed = true;
        }
    }

    /**
     * 返回不可变的包含规则。
     */
    public List<String> includes() {
        return includes;
    }

    /**
     * 返回不可变的排除规则。
     */
    public List<String> excludes() {
        return excludes;
    }

    /**
     * 返回是否将嵌套依赖归档纳入处理范围。
     */
    public boolean encryptDependencies() {
        return encryptDependencies;
    }

    /**
     * 返回操作进度端口。
     */
    public ProgressReporter progress() {
        return progress;
    }

    /**
     * 返回操作取消端口。
     */
    public CancellationProbe cancellation() {
        return cancellation;
    }
}
