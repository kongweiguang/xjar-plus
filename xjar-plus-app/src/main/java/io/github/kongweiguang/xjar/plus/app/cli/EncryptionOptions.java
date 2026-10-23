/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.CancellationToken;
import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.ProgressListener;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;

/**
 * 共享请求映射，避免各 CLI 命令分别定义归档行为或默认值。
 */
public final class EncryptionOptions {
    @Option(names = "--input", required = true, description = "原始 JAR 文件路径。")
    private Path input;

    @Option(names = "--output", required = true, description = "新产物路径或启动器输出目录。")
    private Path output;

    @Option(names = "--include", description = "重复传入以增加加密路径规则。")
    private List<String> includes;

    @Option(names = "--exclude", description = "重复传入以增加排除路径规则。")
    private List<String> excludes;

    @Option(names = "--encrypt-dependencies", description = "显式允许加密归档内的依赖。")
    private boolean encryptDependencies;

    /**
     * 将用户选项映射到稳定的核心请求，并允许注入进度与取消控制。
     */
    EncryptionRequest request(char[] password, ProgressListener progress, CancellationToken cancellation) {
        final EncryptionRequest.Builder builder = EncryptionRequest.builder()
                .input(input)
                .output(output)
                .password(password)
                .encryptDependencies(encryptDependencies)
                .progress(progress)
                .cancellation(cancellation);
        if (includes != null) {
            includes.forEach(builder::include);
        }
        if (excludes != null) {
            excludes.forEach(builder::exclude);
        }
        return builder.build();
    }

    /**
     * 只暴露启动器打包所需的目标路径，不公开其他全部选项字段。
     */
    Path output() {
        return output;
    }

    /**
     * 构建启动器加密配置，并保持请求中约定的目标路径语义。
     */
    EncryptionRequest requestForLauncher(char[] password, ProgressListener progress, CancellationToken cancellation) {
        final EncryptionRequest.Builder builder = EncryptionRequest.builder()
                .input(input)
                .password(password)
                .encryptDependencies(encryptDependencies)
                .progress(progress)
                .cancellation(cancellation);
        if (includes != null) {
            includes.forEach(builder::include);
        }
        if (excludes != null) {
            excludes.forEach(builder::exclude);
        }
        return builder.build();
    }
}
