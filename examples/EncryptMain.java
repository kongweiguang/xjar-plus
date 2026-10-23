/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.examples;

import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import io.github.kongweiguang.xjar.plus.format.Passwords;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * 保持为独立源码示例，也可直接放入项目的测试源码目录。
 */
public final class EncryptMain {
    /**
     * 仅保留可执行入口，避免把示例误用为可复用服务。
     */
    private EncryptMain() {
    }

    /**
     * 通过文件路径读取口令，避免将口令字面量提交到版本库。
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: EncryptMain <input.jar> <output.jar> <password-file>");
        }
        final char[] password = Passwords.readFile(Path.of(args[2]));
        try (EncryptionRequest request = EncryptionRequest.builder()
                .input(Path.of(args[0]))
                .output(Path.of(args[1]))
                .password(password)
                .progress((stage, percent) -> System.out.printf("%3d%% %s%n", percent, stage))
                .build()) {
            final var result = XJarPlus.encrypt(request);
            XJarPlus.verify(result.output(), password);
            System.out.println(result.output());
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
