/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * 将归档条目加密为有界且独立认证的数据块，不向调用方暴露未经认证的数据。
 *
 * @author kongweiguang
 */
public final class ChunkCipher {
    public static final int CHUNK_SIZE = 1024 * 1024;
    private static final byte[] MAGIC = new byte[]{'X', 'J', 'C', '2'};
    private static final int HEADER_LENGTH = 16;
    private static final long MAX_CHUNKS = 0xffffffffL;

    /**
     * 将分块封装固定为下方定义的数据块大小和认证布局。
     */
    private ChunkCipher() {
    }

    /**
     * 写入自描述数据流；输入实际长度与受认证元数据不一致时拒绝处理。
     */
    public static void encrypt(InputStream in, OutputStream out, byte[] key, String artifactId,
                               String path, long length, NonceSequence nonces)
            throws IOException, GeneralSecurityException {
        if (in == null || out == null || nonces == null) {
            throw new IllegalArgumentException("streams and nonces must not be null");
        }
        validateKey(key);
        final byte[] id = FormatSupport.artifactIdBytes(artifactId);
        final byte[] pathBytes = validatedPath(path);
        final long chunkCount = chunkCount(length);
        final byte[] header = header(length);
        final DataOutputStream output = new DataOutputStream(out);
        output.write(header);
        long consumed = 0;
        for (long index = 0; index < chunkCount; index++) {
            final int plainLength = (int) Math.min(CHUNK_SIZE, length - consumed);
            final byte[] plain = new byte[plainLength];
            readExactly(in, plain, plainLength);
            final byte[] nonce = nonces.next();
            final byte[] sealed;
            try {
                sealed = Crypto.seal(key, nonce, aad(header, id, pathBytes, index, plainLength), plain);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
            output.writeInt(plainLength);
            output.write(nonce);
            output.write(sealed);
            consumed += plainLength;
        }
        if (consumed != length) {
            throw new IOException("input length does not match declared length");
        }
        if (in.read() != -1) {
            throw new IOException("input contains bytes beyond declared length");
        }
    }

    /**
     * 提前校验封装头，使加载器在返回资源流之前就能拒绝格式或长度不匹配的产物。
     */
    public static InputStream decryptingStream(InputStream in, byte[] key, String artifactId,
                                               String path, long length) throws IOException {
        if (in == null) {
            throw new IllegalArgumentException("input stream must not be null");
        }
        validateKey(key);
        final byte[] id = FormatSupport.artifactIdBytes(artifactId);
        final byte[] pathBytes = validatedPath(path);
        final long chunks = chunkCount(length);
        final DataInputStream input = new DataInputStream(in);
        final byte[] actualHeader = new byte[HEADER_LENGTH];
        try {
            input.readFully(actualHeader);
        } catch (EOFException e) {
            throw new IOException("encrypted entry header is truncated", e);
        }
        final byte[] expectedHeader = header(length);
        if (!Arrays.equals(expectedHeader, actualHeader)) {
            throw new IOException("encrypted entry header does not match the expected format or length");
        }
        return new ChunkDecryptingInputStream(input, key, id, pathBytes, actualHeader, length, chunks);
    }

    /**
     * 精确读取声明的明文长度，并兼容按规范返回短读的输入流。
     */
    private static void readExactly(InputStream in, byte[] target, int length) throws IOException {
        int offset = 0;
        while (offset < length) {
            final int read = in.read(target, offset, length - offset);
            if (read < 0) {
                throw new EOFException("input is shorter than declared length");
            }
            if (read == 0) {
                final int one = in.read();
                if (one < 0) {
                    throw new EOFException("input is shorter than declared length");
                }
                target[offset++] = (byte) one;
            } else {
                offset += read;
            }
        }
    }

    /**
     * 将固定封装头和条目身份绑定到认证数据，防止有效数据块被移动、重排或改长。
     */
    private static byte[] aad(byte[] header, byte[] artifactId, byte[] path, long chunkIndex, int plainLength) {
        return ByteBuffer.allocate(header.length + artifactId.length + 2 + path.length + 4 + 4)
                .put(header).put(artifactId).putShort((short) path.length).put(path)
                .putInt((int) chunkIndex).putInt(plainLength).array();
    }

    /**
     * 将总长度写入封装头，避免有效前缀被误认为是完整的较短条目。
     */
    private static byte[] header(long length) {
        return ByteBuffer.allocate(HEADER_LENGTH).put(MAGIC).putInt(CHUNK_SIZE).putLong(length).array();
    }

    /**
     * 将条目大小限制在 uint32 块序号范围内，并为零长度内容生成一个经认证的数据块。
     */
    private static long chunkCount(long length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        long chunks = length / CHUNK_SIZE + (length % CHUNK_SIZE == 0 ? 0 : 1);
        if (chunks == 0) {
            chunks = 1;
        }
        if (chunks > MAX_CHUNKS) {
            throw new IllegalArgumentException("entry exceeds the version-2 chunk index limit");
        }
        return chunks;
    }

    /**
     * 将协议密钥固定为 32 字节 AES 密钥，不依赖密码提供方的回退行为。
     */
    private static void validateKey(byte[] key) {
        if (key == null || key.length != Crypto.KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 key must contain 32 bytes");
        }
    }

    /**
     * 只校验一次路径字节，因为路径会进入每个数据块的认证上下文。
     */
    private static byte[] validatedPath(String path) {
        final byte[] pathBytes = FormatSupport.utf8(path);
        if (pathBytes.length == 0 || pathBytes.length > 0xffff || path.startsWith("/")
            || path.endsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0
            || path.contains("//")) {
            throw new IllegalArgumentException("path is not a canonical archive entry path");
        }
        for (String part : path.split("/", -1)) {
            if (".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("path must not contain traversal segments");
            }
        }
        return pathBytes;
    }

    /**
     * 每次只缓冲一个已验证的数据块；标签验证通过前不会向资源使用方交付明文。
     */
    private static final class ChunkDecryptingInputStream extends FilterInputStream {
        private final byte[] key;
        private final byte[] artifactId;
        private final byte[] path;
        private final byte[] header;
        private final long totalLength;
        private final long chunkCount;
        private long nextChunk;
        private long plaintextRead;
        private byte[] current = new byte[0];
        private int offset;
        private boolean closed;
        private boolean endVerified;
        private IOException terminalFailure;

        /**
         * 持有独立密钥副本，以便关闭资源流时能够确定性地清除密钥。
         */
        private ChunkDecryptingInputStream(DataInputStream input, byte[] key, byte[] artifactId, byte[] path,
                                           byte[] header, long totalLength, long chunkCount) {
            super(input);
            this.key = key.clone();
            this.artifactId = artifactId;
            this.path = path;
            this.header = header;
            this.totalLength = totalLength;
            this.chunkCount = chunkCount;
        }

        /**
         * 仅从已通过 GCM 标签验证的数据块中返回一个字节。
         */
        @Override
        public int read() throws IOException {
            ensureOpen();
            try {
                if (!ensureCurrent()) {
                    return -1;
                }
                return current[offset++] & 0xff;
            } catch (IOException e) {
                throw fail(e);
            }
        }

        /**
         * 仅复制已认证的字节，同时保持 InputStream 的边界检查和零长度读取语义。
         */
        @Override
        public int read(byte[] target, int targetOffset, int length) throws IOException {
            ensureOpen();
            if (target == null) {
                throw new NullPointerException("target");
            }
            if (targetOffset < 0 || length < 0 || length > target.length - targetOffset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) {
                return 0;
            }
            try {
                if (!ensureCurrent()) {
                    return -1;
                }
                final int count = Math.min(length, current.length - offset);
                System.arraycopy(current, offset, target, targetOffset, count);
                offset += count;
                return count;
            } catch (IOException e) {
                throw fail(e);
            }
        }

        /**
         * 通过认证读取实现跳过，因为密文偏移量与明文偏移量并不相同。
         */
        @Override
        public long skip(long amount) throws IOException {
            ensureOpen();
            if (amount <= 0) {
                return 0;
            }
            final byte[] scratch = new byte[(int) Math.min(8192, amount)];
            long skipped = 0;
            try {
                while (skipped < amount) {
                    final int count = read(scratch, 0, (int) Math.min(scratch.length, amount - skipped));
                    if (count < 0) {
                        break;
                    }
                    skipped += count;
                }
                return skipped;
            } finally {
                Arrays.fill(scratch, (byte) 0);
            }
        }

        /**
         * 仅报告当前已缓冲且完成认证的明文，不将可读密文误报为可用明文。
         */
        @Override
        public int available() throws IOException {
            ensureOpen();
            return current.length - offset;
        }

        /**
         * 禁止回退读取，因为 nonce 顺序和认证块状态被设计为单向推进。
         */
        @Override
        public boolean markSupported() {
            return false;
        }

        /**
         * 归档流关闭时清除保留的密钥和明文缓冲区。
         */
        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                Arrays.fill(key, (byte) 0);
                Arrays.fill(current, (byte) 0);
                super.close();
            }
        }

        /**
         * 读取并认证下一个规定大小的数据块，或确认数据流末尾没有多余字节。
         */
        private boolean ensureCurrent() throws IOException {
            while (offset >= current.length) {
                if (nextChunk >= chunkCount) {
                    verifyEnd();
                    return false;
                }
                loadChunk();
            }
            return true;
        }

        /**
         * 保留明文前先校验长度和标签，并在交付最后一个数据块前检查是否到达流末尾。
         */
        private void loadChunk() throws IOException {
            Arrays.fill(current, (byte) 0);
            current = new byte[0];
            final int expectedLength = (int) Math.min(CHUNK_SIZE, totalLength - plaintextRead);
            final int storedLength;
            try {
                storedLength = ((DataInputStream) in).readInt();
            } catch (EOFException e) {
                throw new IOException("encrypted entry block is truncated", e);
            }
            if (storedLength != expectedLength) {
                throw new IOException("encrypted entry block length does not match metadata");
            }
            final byte[] nonce = new byte[Crypto.NONCE_BYTES];
            final byte[] sealed = new byte[expectedLength + Crypto.TAG_BYTES];
            try {
                ((DataInputStream) in).readFully(nonce);
                ((DataInputStream) in).readFully(sealed);
            } catch (EOFException e) {
                throw new IOException("encrypted entry block is truncated", e);
            }
            try {
                current = Crypto.open(key, nonce,
                        aad(header, artifactId, path, nextChunk, expectedLength), sealed);
            } catch (GeneralSecurityException e) {
                throw new IOException("encrypted entry block authentication failed", e);
            }
            if (current.length != expectedLength) {
                Arrays.fill(current, (byte) 0);
                throw new IOException("authenticated block has an invalid plaintext length");
            }
            plaintextRead += current.length;
            offset = 0;
            nextChunk++;
            if (nextChunk == chunkCount) {
                verifyEnd();
            }
        }

        /**
         * 拒绝尾随字节，使截断检测不依赖 ZIP 容器的具体行为。
         */
        private void verifyEnd() throws IOException {
            if (!endVerified) {
                if (((DataInputStream) in).read() != -1) {
                    Arrays.fill(current, (byte) 0);
                    current = new byte[0];
                    throw new IOException("encrypted entry contains trailing bytes");
                }
                if (plaintextRead != totalLength) {
                    Arrays.fill(current, (byte) 0);
                    current = new byte[0];
                    throw new IOException("encrypted entry is shorter than its authenticated total length");
                }
                endVerified = true;
            }
        }

        /**
         * 保持 InputStream 的关闭后行为，避免流所有权结束后继续读取旧缓冲区。
         */
        private void ensureOpen() throws IOException {
            if (terminalFailure != null) {
                throw terminalFailure;
            }
            if (closed) {
                throw new IOException("stream is closed");
            }
        }

        /**
         * 将格式错误或不可读密文标记为终止失败，避免重试损坏的数据流后误报为 EOF。
         */
        private IOException fail(IOException failure) {
            if (terminalFailure == null) {
                terminalFailure = failure;
                closed = true;
                Arrays.fill(key, (byte) 0);
                Arrays.fill(current, (byte) 0);
                current = new byte[0];
                try {
                    super.close();
                } catch (IOException closeFailure) {
                    terminalFailure.addSuppressed(closeFailure);
                }
            }
            return terminalFailure;
        }
    }
}
