/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PasswordOptionsTest {
    /**
     * 验证未指定凭据来源时，CLI 默认不会读取应用标准输入。
     */
    @Test
    void doesNotReadStandardInputImplicitly() {
        Assumptions.assumeTrue(System.console() == null);
        final PasswordOptions options = new PasswordOptions();
        final InputStream original = System.in;
        final ByteArrayInputStream input = new ByteArrayInputStream("still here".getBytes(StandardCharsets.UTF_8));
        System.setIn(input);
        try {
            assertThrows(IllegalArgumentException.class, options::resolve);
            assertEquals('s', input.read());
        } finally {
            System.setIn(original);
        }
    }

    /**
     * 验证显式标准输入模式只解码一行 Unicode 密码，并保留后续应用输入。
     */
    @Test
    void readsOneUtf8LineOnlyWhenRequested() throws Exception {
        final PasswordOptions options = new PasswordOptions();
        new CommandLine(options).parseArgs("--password-stdin");
        final InputStream original = System.in;
        final ByteArrayInputStream input = new ByteArrayInputStream("秘密🔐\napplication input"
                .getBytes(StandardCharsets.UTF_8));
        System.setIn(input);
        char[] password = null;
        try {
            password = options.resolve();
            assertArrayEquals("秘密🔐".toCharArray(), password);
            assertEquals('a', input.read());
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
            System.setIn(original);
        }
    }

    /**
     * 读取标准输入前拒绝冲突的密码来源，避免配置错误时误取应用输入。
     */
    @Test
    void rejectsMultiplePasswordSourcesWithoutReadingInput() {
        Assumptions.assumeTrue(System.console() == null);
        final PasswordOptions options = new PasswordOptions();
        new CommandLine(options).parseArgs("--password-file", "secret.txt", "--password-stdin");
        final InputStream original = System.in;
        final ByteArrayInputStream input = new ByteArrayInputStream("still here".getBytes(StandardCharsets.UTF_8));
        System.setIn(input);
        try {
            assertThrows(IllegalArgumentException.class, options::resolve);
            assertEquals('s', input.read());
        } finally {
            System.setIn(original);
        }
    }
}
