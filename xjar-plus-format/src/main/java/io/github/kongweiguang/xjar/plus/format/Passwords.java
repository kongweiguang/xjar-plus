/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * 读取密码文件时保留有意义的空格，不对 Unicode 输入做归一化。
 *
 * @author kongweiguang
 */
public final class Passwords {
    public static final int MAX_FILE_BYTES = 64 * 1024;

    /**
     * 在调用位置显式读取密码文件，避免将状态隐藏在单例中。
     */
    private Passwords() {
    }

    /**
     * 仅移除末尾一个换行序列，保留密码中其他由用户提供的字符。
     */
    public static char[] readFile(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        final byte[] bytes = readLimited(path);
        try {
            return decode(bytes, stripOneLineEnding(bytes));
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /**
     * 最多多读一个字节来判断是否超限，避免文件增长导致无界分配密码数据。
     */
    private static byte[] readLimited(Path path) throws IOException {
        final byte[] buffer = new byte[MAX_FILE_BYTES + 1];
        int byteCount = 0;
        try {
            try (InputStream input = Files.newInputStream(path)) {
                while (byteCount < buffer.length) {
                    final int read = input.read(buffer, byteCount, buffer.length - byteCount);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        final int one = input.read();
                        if (one < 0) {
                            break;
                        }
                        buffer[byteCount++] = (byte) one;
                    } else {
                        byteCount += read;
                    }
                }
                if (byteCount > MAX_FILE_BYTES || input.read() != -1) {
                    throw new IOException("password file exceeds 64 KiB");
                }
            }
            return Arrays.copyOf(buffer, byteCount);
        } finally {
            Arrays.fill(buffer, (byte) 0);
        }
    }

    /**
     * 保留密码的全部内容，仅移除末尾一个换行序列。
     */
    private static int stripOneLineEnding(byte[] bytes) {
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\n') {
            length--;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
        } else if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return length;
    }

    /**
     * 严格按 UTF-8 解码，避免格式错误的文件内容在密钥派生前被静默改写。
     */
    private static char[] decode(byte[] bytes, int length) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, 0, length)).toString().toCharArray();
        } catch (CharacterCodingException e) {
            throw new IOException("password file is not valid UTF-8", e);
        }
    }
}
