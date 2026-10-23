/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.format.Passwords;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 自动化场景必须显式指定密码来源，避免意外占用应用标准输入。
 */
public final class PasswordOptions {
    @Option(names = "--password-file", description = "从 UTF-8 文件读取密码（最多 64 KiB）。")
    private Path passwordFile;

    @Option(names = "--password-stdin", description = "从标准输入读取一行密码；仅供工具命令使用。")
    private boolean passwordStdin;

    /**
     * 解析唯一明确的密码来源，并在核心操作开始前拒绝冲突配置。
     */
    public char[] resolve() throws IOException {
        final int explicitSources = (passwordFile == null ? 0 : 1) + (passwordStdin ? 1 : 0);
        if (explicitSources > 1) {
            throw new IllegalArgumentException("--password-file 与 --password-stdin 不能同时使用。");
        }
        if (passwordFile != null) {
            return requirePassword(Passwords.readFile(passwordFile));
        }
        if (passwordStdin) {
            return requirePassword(readStdinLine());
        }
        if (System.console() == null) {
            throw new IllegalArgumentException("请提供 --password-file 或 --password-stdin；当前没有可用控制台。");
        }
        final char[] value = System.console().readPassword("密码: ");
        if (value == null) {
            throw new IllegalArgumentException("未读取到密码。");
        }
        return requirePassword(value);
    }

    /**
     * 读取有界 UTF-8 行，避免管道秘密无限增长或隐式占用标准输入。
     */
    private char[] readStdinLine() throws IOException {
        final byte[] encoded = new byte[65_536];
        int length = 0;
        try {
            int next;
            while ((next = System.in.read()) != -1 && next != '\n') {
                if (length == encoded.length) {
                    throw new IOException("标准输入密码超过 64 KiB 限制。");
                }
                encoded[length++] = (byte) next;
            }
            if (length > 0 && encoded[length - 1] == '\r') {
                length--;
            }
            final CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded, 0, length));
            final char[] secret = new char[decoded.remaining()];
            decoded.get(secret);
            if (decoded.hasArray()) {
                java.util.Arrays.fill(decoded.array(), '\0');
            }
            return secret;
        } catch (CharacterCodingException e) {
            throw new IOException("标准输入密码不是有效 UTF-8。", e);
        } finally {
            java.util.Arrays.fill(encoded, (byte) 0);
        }
    }

    /**
     * 在输入边界拒绝空密码，并在返回校验错误前清除密码内容。
     */
    private char[] requirePassword(char[] value) {
        if (value == null || value.length == 0) {
            if (value != null) {
                java.util.Arrays.fill(value, '\0');
            }
            throw new IllegalArgumentException("密码不能为空。");
        }
        return value;
    }
}
