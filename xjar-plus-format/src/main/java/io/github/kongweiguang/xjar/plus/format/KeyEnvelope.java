/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.Arrays;

/**
 * 使用密码保护随机数据密钥，并通过 GCM 认证绑定密钥身份和 KDF 参数。
 *
 * @author kongweiguang
 */
public final class KeyEnvelope implements AutoCloseable {
    public static final int PBKDF2_ITERATIONS = 600_000;
    private static final byte[] MAGIC = new byte[]{'X', 'K', 'E', '2'};
    private static final int ENCODED_LENGTH = 100;
    private static final int HEADER_LENGTH = 40;

    private final byte[] artifactId;
    private final byte[] salt;
    private final int iterations;
    private final byte[] nonce;
    private final byte[] wrappedDataKey;
    private byte[] dataKey;
    private boolean closed;

    /**
     * 复制所有编码字段，避免调用方持有的缓冲区改变后续认证输入。
     */
    private KeyEnvelope(byte[] artifactId, byte[] salt, int iterations, byte[] nonce,
                        byte[] wrappedDataKey, byte[] dataKey) {
        this.artifactId = artifactId.clone();
        this.salt = salt.clone();
        this.iterations = iterations;
        this.nonce = nonce.clone();
        this.wrappedDataKey = wrappedDataKey.clone();
        this.dataKey = dataKey == null ? null : dataKey.clone();
    }

    /**
     * 创建绑定到具体产物的密钥信封，使内容密钥不直接依赖用户密码。
     */
    public static KeyEnvelope create(char[] password) throws GeneralSecurityException {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        final byte[] id = Crypto.randomBytes(16);
        id[6] = (byte) ((id[6] & 0x0f) | 0x40);
        id[8] = (byte) ((id[8] & 0x3f) | 0x80);
        final byte[] salt = Crypto.randomBytes(16);
        final byte[] nonce = Crypto.randomBytes(Crypto.NONCE_BYTES);
        final byte[] dataKey = Crypto.randomBytes(Crypto.KEY_BYTES);
        byte[] wrappingKey = null;
        try {
            wrappingKey = Crypto.derive(password, salt, PBKDF2_ITERATIONS);
            final byte[] wrapped = Crypto.seal(wrappingKey, nonce,
                    header(id, salt, PBKDF2_ITERATIONS), dataKey);
            return new KeyEnvelope(id, salt, PBKDF2_ITERATIONS, nonce, wrapped, dataKey);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
            if (wrappingKey != null) {
                Arrays.fill(wrappingKey, (byte) 0);
            }
        }
    }

    /**
     * 仅解析精确的版本 2 结构和固定 KDF 成本，避免攻击者控制密钥派生的计算量。
     */
    public static KeyEnvelope parse(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_LENGTH) {
            throw new IllegalArgumentException("XKE2 envelope has an invalid length");
        }
        final ByteBuffer input = ByteBuffer.wrap(encoded);
        final byte[] magic = new byte[4];
        input.get(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw new IllegalArgumentException("unsupported key envelope format");
        }
        final byte[] id = new byte[16];
        final byte[] salt = new byte[16];
        input.get(id).get(salt);
        final int iterations = input.getInt();
        if (iterations != PBKDF2_ITERATIONS) {
            throw new IllegalArgumentException("XKE2 requires exactly 600000 PBKDF2 iterations");
        }
        final byte[] nonce = new byte[Crypto.NONCE_BYTES];
        final byte[] wrapped = new byte[Crypto.KEY_BYTES + Crypto.TAG_BYTES];
        input.get(nonce).get(wrapped);
        return new KeyEnvelope(id, salt, iterations, nonce, wrapped, null);
    }

    /**
     * 构造受认证的公开头部，使 salt、计算成本或产物身份被修改时解包失败。
     */
    private static byte[] header(byte[] id, byte[] salt, int iterations) {
        return ByteBuffer.allocate(HEADER_LENGTH).put(MAGIC).put(id).put(salt).putInt(iterations).array();
    }

    /**
     * 仅序列化封装后的密钥和公开派生元数据，绝不序列化数据密钥明文。
     */
    public synchronized byte[] encode() {
        return ByteBuffer.allocate(ENCODED_LENGTH)
                .put(MAGIC).put(artifactId).put(salt).putInt(iterations).put(nonce).put(wrappedDataKey).array();
    }

    /**
     * 只有 GCM 完整认证头部后才解包；返回的密钥副本归调用方所有，调用方必须负责清除。
     */
    public synchronized byte[] unwrap(char[] password) throws GeneralSecurityException {
        ensureOpen();
        final byte[] wrappingKey = Crypto.derive(password, salt, iterations);
        try {
            final byte[] unwrapped = Crypto.open(wrappingKey, nonce,
                    header(artifactId, salt, iterations), wrappedDataKey);
            if (unwrapped.length != Crypto.KEY_BYTES) {
                Arrays.fill(unwrapped, (byte) 0);
                throw new GeneralSecurityException("wrapped data key has an invalid length");
            }
            if (dataKey != null) {
                Arrays.fill(dataKey, (byte) 0);
            }
            dataKey = unwrapped.clone();
            return unwrapped;
        } finally {
            Arrays.fill(wrappingKey, (byte) 0);
        }
    }

    /**
     * 仅在信封开启期间返回防御性密钥副本；调用方使用完毕后必须清除副本。
     */
    public synchronized byte[] getDataKey() {
        ensureOpen();
        if (dataKey == null) {
            throw new IllegalStateException("data key is available only after create or unwrap");
        }
        return dataKey.clone();
    }

    /**
     * 返回 Java 与 Go 共用的规范 UUID，其线格式表示仍为 16 字节。
     */
    public synchronized String getArtifactId() {
        return FormatSupport.artifactIdString(artifactId);
    }

    /**
     * 清除保留的数据密钥明文，同时保留信封序列化所需的非秘密线格式字段。
     */
    @Override
    public synchronized void close() {
        if (!closed) {
            if (dataKey != null) {
                Arrays.fill(dataKey, (byte) 0);
                dataKey = null;
            }
            closed = true;
        }
    }

    /**
     * 数据密钥明文销毁后禁止复用已关闭的信封。
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("key envelope is closed");
        }
    }
}
