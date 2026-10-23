/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * 验证经过认证的归档内容，但不写出恢复后的文件。
 */
@Command(name = "verify", mixinStandardHelpOptions = true, description = "验证加密 JAR 的密码和认证数据。")
public final class VerifyCommand implements Callable<Integer> {
    @Option(names = "--input", required = true, description = "加密 JAR 文件路径。")
    private Path input;

    @Mixin
    private PasswordOptions password;

    /**
     * 调用核心验证，并始终清除已解析的密码字符数组。
     */
    @Override
    public Integer call() throws Exception {
        final char[] secret = password.resolve();
        try {
            CommandOutput.printArchive(XJarPlus.verify(input, secret));
            return 0;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }
}
