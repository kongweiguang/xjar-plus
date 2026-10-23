/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 一个稳定 appId 对应的私有签发凭据；调用方不得将编码后的凭据文件放入发行包或日志。
 *
 * @author kongweiguang
 */
public final class IssuerCredentials implements AutoCloseable {
    private static final byte[] MAGIC = new byte[]{'X', 'J', 'I', '2'};
    private final String appId;
    private final byte[] licenseKey;
    private boolean closed;

    /**
     * 复制私钥，避免调用方修改原数组后改变此签发凭据的身份。
     */
    private IssuerCredentials(String appId, byte[] licenseKey) {
        this.appId = appId;
        this.licenseKey = licenseKey.clone();
    }

    /**
     * 创建可复用的签发密钥；凭据接管密钥后立即清除临时随机缓冲区。
     */
    public static IssuerCredentials create(String appId) {
        validateAppId(appId);
        final byte[] licenseKey = Crypto.randomBytes(Crypto.KEY_BYTES);
        try {
            return new IssuerCredentials(appId, licenseKey);
        } finally {
            Arrays.fill(licenseKey, (byte) 0);
        }
    }

    /**
     * 按 XJI2 二进制协议精确解析，并拒绝尾随字节，避免签发凭据身份产生歧义。
     */
    public static IssuerCredentials parse(byte[] encoded) {
        if (encoded == null || encoded.length < 4 + 4 + 1 + Crypto.KEY_BYTES
            || encoded.length > 4 + 4 + 256 + Crypto.KEY_BYTES) {
            throw new IllegalArgumentException("XJI2 issuer credentials have an invalid length");
        }
        final ByteBuffer input = ByteBuffer.wrap(encoded);
        final byte[] magic = new byte[4];
        input.get(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw new IllegalArgumentException("unsupported issuer credential format");
        }
        final long idLength = input.getInt() & 0xffffffffL;
        if (idLength < 1 || idLength > 256 || input.remaining() != idLength + Crypto.KEY_BYTES) {
            throw new IllegalArgumentException("XJI2 appId length is invalid");
        }
        final byte[] idBytes = new byte[(int) idLength];
        input.get(idBytes);
        final String appId = FormatSupport.decodeUtf8(idBytes);
        validateAppId(appId);
        final byte[] key = new byte[Crypto.KEY_BYTES];
        input.get(key);
        try {
            return new IssuerCredentials(appId, key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /**
     * 按跨语言 UTF-8 字节长度限制校验，而不依赖 Java 字符数。
     */
    static void validateAppId(String appId) {
        final byte[] bytes = FormatSupport.utf8(appId);
        if (bytes.length < 1 || bytes.length > 256) {
            throw new IllegalArgumentException("appId must contain between 1 and 256 UTF-8 bytes");
        }
    }

    /**
     * 将 XJI2 编码到精确大小的结果缓冲区，避免内部流缓冲区额外保留一份密钥副本。
     */
    public synchronized byte[] encode() {
        ensureOpen();
        final byte[] idBytes = FormatSupport.utf8(appId);
        try {
            return ByteBuffer.allocate(4 + 4 + idBytes.length + licenseKey.length)
                    .put(MAGIC).putInt(idBytes.length).put(idBytes).put(licenseKey).array();
        } finally {
            Arrays.fill(idBytes, (byte) 0);
        }
    }

    /**
     * 凭据开启期间返回稳定 appId，确保重新构建产物后续期仍绑定同一应用。
     */
    public synchronized String getAppId() {
        ensureOpen();
        return appId;
    }

    /**
     * 返回由调用方持有的防御性副本；调用方应清除副本，且更换密钥会使已签发的许可证失效。
     */
    public synchronized byte[] getLicenseKey() {
        ensureOpen();
        return licenseKey.clone();
    }

    /**
     * 清除保留的签发密钥，使调用方能够限制凭据签发完成后的内存驻留时间。
     */
    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(licenseKey, (byte) 0);
            closed = true;
        }
    }

    /**
     * 拒绝关闭后的访问，避免已清零的凭据被误当作可用签发密钥。
     */
    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("issuer credentials are closed");
        }
    }
}
