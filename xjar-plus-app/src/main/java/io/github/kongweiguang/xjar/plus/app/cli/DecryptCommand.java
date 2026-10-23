/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * 将解密结果写入独立路径，确保失败时加密源文件仍可恢复。
 */
@Command(name = "decrypt", mixinStandardHelpOptions = true, description = "使用密码恢复加密 JAR。")
public final class DecryptCommand implements Callable<Integer> {
    @Option(names = "--input", required = true, description = "待恢复的加密 JAR 文件路径。")
    private Path input;

    @Option(names = "--output", required = true, description = "新明文 JAR 文件路径。")
    private Path output;

    @Mixin
    private PasswordOptions password;

    /**
     * 调用核心解密操作，并在成功或失败后清除密码字符数组。
     */
    @Override
    public Integer call() throws Exception {
        try (CliCancellationSession cancellation = new CliCancellationSession()) {
            final char[] secret = password.resolve();
            try {
                try (EncryptionRequest request = EncryptionRequest.builder()
                        .input(input)
                        .output(output)
                        .password(secret)
                        .progress(new ConsoleProgress())
                        .cancellation(cancellation)
                        .build()) {
                    CommandOutput.printBuild(XJarPlus.decrypt(request));
                }
                return 0;
            } finally {
                Arrays.fill(secret, '\0');
            }
        }
    }
}
