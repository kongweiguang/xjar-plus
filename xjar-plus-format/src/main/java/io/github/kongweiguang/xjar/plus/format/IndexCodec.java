/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 将归档元数据作为一个有界 AES-GCM 数据整体认证，通过后才对外提供解析结果。
 *
 * @author kongweiguang
 */
public final class IndexCodec {
    public static final int MAX_ENTRIES = 1_000_000;
    public static final int MAX_INDEX_BYTES = 64 * 1024 * 1024;
    private static final byte[] MAGIC = new byte[]{'X', 'J', 'X', '2'};

    /**
     * 集中管理序列化规则，避免调用方生成未经认证的索引变体。
     */
    private IndexCodec() {
    }

    /**
     * 加密前先编码完整的确定性索引，避免部分认证的元数据逸出。
     */
    public static byte[] seal(ArchiveIndex index, byte[] key, String artifactId, NonceSequence nonces)
            throws GeneralSecurityException {
        if (index == null || nonces == null) {
            throw new IllegalArgumentException("index and nonces must not be null");
        }
        final byte[] plain = encodeEntries(index.entries());
        if (plain.length > MAX_INDEX_BYTES) {
            throw new IllegalArgumentException("archive index exceeds 64 MiB");
        }
        final byte[] nonce = nonces.next();
        final byte[] aad = aad(artifactId);
        final byte[] encrypted = Crypto.seal(key, nonce, aad, plain);
        return ByteBuffer.allocate(MAGIC.length + nonce.length + encrypted.length)
                .put(MAGIC).put(nonce).put(encrypted).array();
    }

    /**
     * 解析前先验证 GCM，确保被篡改的路径、标志、长度或摘要不会作为元数据返回。
     */
    public static ArchiveIndex open(byte[] encoded, byte[] key, String artifactId)
            throws GeneralSecurityException {
        if (encoded == null || encoded.length < 4 + Crypto.NONCE_BYTES + Crypto.TAG_BYTES
            || encoded.length > MAX_INDEX_BYTES + 4 + Crypto.NONCE_BYTES + Crypto.TAG_BYTES) {
            throw new IllegalArgumentException("XJX2 index has an invalid length");
        }
        final ByteBuffer input = ByteBuffer.wrap(encoded);
        final byte[] magic = new byte[4];
        input.get(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw new IllegalArgumentException("unsupported archive index format");
        }
        final byte[] nonce = new byte[Crypto.NONCE_BYTES];
        input.get(nonce);
        final byte[] ciphertext = new byte[input.remaining()];
        input.get(ciphertext);
        final byte[] plain = Crypto.open(key, nonce, aad(artifactId), ciphertext);
        if (plain.length > MAX_INDEX_BYTES) {
            throw new IllegalArgumentException("archive index exceeds 64 MiB");
        }
        return decodeEntries(plain);
    }

    /**
     * 按网络字节序写入定长字段，并按路径的 UTF-8 编码字节排序。
     */
    private static byte[] encodeEntries(List<EntryMetadata> entries) {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(entries.size());
            for (EntryMetadata entry : entries) {
                final byte[] path = FormatSupport.utf8(entry.getPath());
                output.writeShort(path.length);
                output.write(path);
                output.writeByte(entry.isEncrypted() ? 1 : 0);
                output.writeLong(entry.getLength());
                output.writeInt((int) entry.getCrc32());
                output.write(entry.getSha256());
                if (bytes.size() > MAX_INDEX_BYTES) {
                    throw new IllegalArgumentException("archive index exceeds 64 MiB");
                }
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory index encoding failed", e);
        }
    }

    /**
     * 返回索引前拒绝未知标志位、重复路径、截断数据和尾随字段。
     */
    private static ArchiveIndex decodeEntries(byte[] plain) {
        try {
            final DataInputStream input = new DataInputStream(new ByteArrayInputStream(plain));
            final int count = input.readInt();
            if (count < 0 || count > MAX_ENTRIES) {
                throw new IllegalArgumentException("archive index entry count is invalid");
            }
            final List<EntryMetadata> entries = new ArrayList<EntryMetadata>(count);
            for (int i = 0; i < count; i++) {
                final int pathLength = input.readUnsignedShort();
                if (pathLength == 0) {
                    throw new IllegalArgumentException("archive index path is empty");
                }
                final byte[] pathBytes = new byte[pathLength];
                input.readFully(pathBytes);
                final String path = FormatSupport.decodeUtf8(pathBytes);
                final int flags = input.readUnsignedByte();
                if (flags != 0 && flags != 1) {
                    throw new IllegalArgumentException("archive index entry has unknown flags");
                }
                final long length = input.readLong();
                final long crc32 = input.readInt() & 0xffffffffL;
                final byte[] sha256 = new byte[32];
                input.readFully(sha256);
                entries.add(new EntryMetadata(path, flags == 1, length, crc32, sha256));
            }
            if (input.read() != -1) {
                throw new IllegalArgumentException("archive index has trailing bytes");
            }
            return new ArchiveIndex(entries);
        } catch (IOException e) {
            throw new IllegalArgumentException("archive index is truncated", e);
        }
    }

    /**
     * 将加密索引同时绑定到格式标识和产物密钥身份。
     */
    private static byte[] aad(String artifactId) {
        return ByteBuffer.allocate(MAGIC.length + 16).put(MAGIC)
                .put(FormatSupport.artifactIdBytes(artifactId)).array();
    }
}
