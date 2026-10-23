/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

/**
 * 保存单个归档条目的不可变认证信息；即使条目原样保留，也会记录 SHA-256。
 *
 * @author kongweiguang
 */
public final class EntryMetadata {
    private final String path;
    private final boolean encrypted;
    private final long length;
    private final long crc32;
    private final byte[] sha256;

    /**
     * 一次性校验归档名称，避免索引接收有歧义或包含路径穿越的条目。
     */
    public EntryMetadata(String path, boolean encrypted, long length, long crc32, byte[] sha256) {
        validatePath(path);
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        if (crc32 < 0 || crc32 > 0xffffffffL) {
            throw new IllegalArgumentException("crc32 must be an unsigned 32-bit value");
        }
        if (sha256 == null || sha256.length != 32) {
            throw new IllegalArgumentException("sha256 must contain 32 bytes");
        }
        this.path = path;
        this.encrypted = encrypted;
        this.length = length;
        this.crc32 = crc32;
        this.sha256 = sha256.clone();
    }

    /**
     * 在路径成为归档认证身份前，拒绝有歧义的分隔符和路径穿越。
     */
    private static void validatePath(String path) {
        final byte[] pathBytes = FormatSupport.utf8(path);
        if (pathBytes.length == 0 || pathBytes.length > 0xffff || path.startsWith("/")
            || path.endsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
            || path.contains("//")) {
            throw new IllegalArgumentException("path is not a canonical archive entry path");
        }
        validateSegments(path);
    }

    /**
     * 将路径穿越检查与编码检查分开，确保嵌套 JAR 的分隔路径仍可合法使用。
     */
    private static void validateSegments(String path) {
        for (final String part : path.split("/", -1)) {
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("path must not contain traversal segments");
            }
        }
    }

    /**
     * 返回以斜杠分隔的归档身份，供 AAD 和确定性索引键使用。
     */
    public String getPath() {
        return path;
    }

    /**
     * 标记归档是否以经认证的密文保存此条目。
     */
    public boolean isEncrypted() {
        return encrypted;
    }

    /**
     * 返回原始字节长度，使解密流能在交付内容前拒绝截断数据。
     */
    public long getLength() {
        return length;
    }

    /**
     * 返回与 ZIP 兼容的无符号 CRC32 值，保持元数据一致。
     */
    public long getCrc32() {
        return crc32;
    }

    /**
     * 返回副本，避免调用方修改此值对象持有的已认证摘要。
     */
    public byte[] getSha256() {
        return sha256.clone();
    }
}
