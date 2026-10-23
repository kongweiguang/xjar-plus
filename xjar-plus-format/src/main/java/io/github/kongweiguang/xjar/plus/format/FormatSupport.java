/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * 共享严格编码规则，确保 Java 与 Go 的线格式逐字节一致。
 *
 * @author kongweiguang
 */
final class FormatSupport {
    /**
     * 将线格式辅助方法限制在包内，避免调用方绕过公开格式校验。
     */
    private FormatSupport() {
    }

    /**
     * 编码协议文本时拒绝格式错误的 UTF-16，而不静默替换字符。
     */
    static byte[] utf8(String value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        try {
            final ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
            final byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("value is not valid Unicode", e);
        }
    }

    /**
     * 严格解码协议文本，避免损坏的标识符被归一化为另一个值。
     */
    static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("protocol text is not valid UTF-8", e);
        }
    }

    /**
     * 将规范 UUID 转换为协议规定的固定长度表示。
     */
    static byte[] artifactIdBytes(String artifactId) {
        if (artifactId == null) {
            throw new IllegalArgumentException("artifactId must not be null");
        }
        try {
            final UUID uuid = UUID.fromString(artifactId);
            if (!uuid.toString().equals(artifactId)) {
                throw new IllegalArgumentException("artifactId must be a canonical UUID");
            }
            return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits()).array();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("artifactId must be a canonical UUID", e);
        }
    }

    /**
     * 将恰好 16 个协议字节转换为 Java 与 Go 共用的规范标识符。
     */
    static String artifactIdString(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("artifactId must contain 16 bytes");
        }
        final ByteBuffer input = ByteBuffer.wrap(bytes);
        return new UUID(input.getLong(), input.getLong()).toString();
    }

    /**
     * 按网络字节序写入定长二进制字段，不依赖平台默认设置。
     */
    static byte[] write(Writer writer) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream output = new DataOutputStream(bytes);
            writer.write(output);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory protocol encoding failed", e);
        }
    }

    /**
     * 封装需检查异常的写入操作，使所有协议编码器共用相同的大端序路径。
     */
    interface Writer {
        /**
         * 按调用方指定的字段顺序写入一条内存中的协议记录。
         */
        void write(DataOutputStream output) throws IOException;
    }
}
