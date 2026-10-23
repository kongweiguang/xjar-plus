/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.api;

import java.io.Console;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 可运行的测试源码示例，供偏好直接编写 Java main 而非 CLI 参数的用户参考。
 */
public final class ApiMainExample {
    private ApiMainExample() {
    }

    /**
     * 交互式读取秘密，避免 main 方法示例教用户把密码暴露在命令行参数中。
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: ApiMainExample <input.jar> <output.jar>");
        }
        final Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("Run this example in a terminal with an interactive console");
        }
        final char[] password = console.readPassword("Archive password: ");
        try {
            final BuildResult result = XJarPlus.encrypt(EncryptionRequest.builder()
                    .input(Path.of(args[0]))
                    .output(Path.of(args[1]))
                    .password(password)
                    .build());
            console.printf("Created: %s%n", result.output());
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
