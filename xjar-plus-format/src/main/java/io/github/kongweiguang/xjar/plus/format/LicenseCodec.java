/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;

/**
 * 使用 AES-GCM 和跨语言固定许可证 AAD 字符串编解码 XJL2 许可证。
 *
 * @author kongweiguang
 */
public final class LicenseCodec {
    private static final byte[] MAGIC = new byte[]{'X', 'J', 'L', '2'};
    private static final byte[] AAD = "xjar-plus/license/v2".getBytes(StandardCharsets.UTF_8);
    private static final int MIN_ENCODED_LENGTH = 4 + Crypto.NONCE_BYTES + 4 + 1 + 16 + Crypto.TAG_BYTES;
    private static final int MAX_ENCODED_LENGTH = 4 + Crypto.NONCE_BYTES + 4 + 256 + 16 + Crypto.TAG_BYTES;

    /**
     * 使所有许可证记录遵循同一套带版本的 AES-GCM 与 AAD 协议。
     */
    private LicenseCodec() {
    }

    /**
     * 使用新 nonce 签发许可证，避免稳定签发密钥在多次续期时重复使用 GCM nonce。
     */
    public static byte[] issue(IssuerCredentials credentials, Instant notBefore, Instant notAfter)
            throws GeneralSecurityException {
        if (credentials == null || notBefore == null) {
            throw new IllegalArgumentException("credentials and notBefore must not be null");
        }
        IssuerCredentials.validateAppId(credentials.getAppId());
        final long start = notBefore.getEpochSecond();
        final long end = notAfter == null ? 0 : notAfter.getEpochSecond();
        validateInterval(start, end);
        return issueWithNonce(credentials, start, end, Crypto.randomBytes(Crypto.NONCE_BYTES));
    }

    /**
     * 完整认证并解析许可证后，才向启动器返回有效期信息。
     */
    public static LicenseGrant open(byte[] encoded, byte[] licenseKey, String expectedAppId)
            throws GeneralSecurityException {
        validateOpenInputs(encoded, licenseKey, expectedAppId);
        return decodePlaintext(decrypt(encoded, licenseKey), expectedAppId);
    }

    /**
     * 在分配密文缓冲区或调用密码提供方前，先检查外层长度和身份信息。
     */
    private static void validateOpenInputs(byte[] encoded, byte[] licenseKey, String expectedAppId) {
        if (encoded == null || encoded.length < MIN_ENCODED_LENGTH || encoded.length > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("XJL2 license has an invalid length");
        }
        if (licenseKey == null || licenseKey.length != Crypto.KEY_BYTES) {
            throw new IllegalArgumentException("license key must contain 32 bytes");
        }
        IssuerCredentials.validateAppId(expectedAppId);
    }

    /**
     * 检查固定 XJL2 头部，并在解析字段前认证完整载荷。
     */
    private static byte[] decrypt(byte[] encoded, byte[] licenseKey) throws GeneralSecurityException {
        final ByteBuffer input = ByteBuffer.wrap(encoded);
        final byte[] magic = new byte[4];
        input.get(magic);
        if (!Arrays.equals(MAGIC, magic)) {
            throw new IllegalArgumentException("unsupported license format");
        }
        final byte[] nonce = new byte[Crypto.NONCE_BYTES];
        input.get(nonce);
        final byte[] ciphertext = new byte[input.remaining()];
        input.get(ciphertext);
        return Crypto.open(licenseKey, nonce, AAD, ciphertext);
    }

    /**
     * 精确解析明文字段，并校验应用绑定关系和左闭右开的有效期区间。
     */
    private static LicenseGrant decodePlaintext(byte[] plain, String expectedAppId)
            throws GeneralSecurityException {
        if (plain.length < 4 + 1 + 16) {
            throw new IllegalArgumentException("XJL2 license payload is truncated");
        }
        final ByteBuffer payload = ByteBuffer.wrap(plain);
        final long idLength = payload.getInt() & 0xffffffffL;
        if (idLength < 1 || idLength > 256 || payload.remaining() != idLength + 16) {
            throw new IllegalArgumentException("XJL2 appId length is invalid");
        }
        final byte[] idBytes = new byte[(int) idLength];
        payload.get(idBytes);
        final String appId = FormatSupport.decodeUtf8(idBytes);
        if (!Arrays.equals(idBytes, FormatSupport.utf8(expectedAppId))) {
            throw new GeneralSecurityException("license appId does not match the expected application");
        }
        final long start = payload.getLong();
        final long end = payload.getLong();
        validateInterval(start, end);
        return new LicenseGrant(appId, start, end);
    }

    /**
     * 有效期起点为非负且包含在内，终点不包含在内；零值专用于表示永久有效。
     */
    private static void validateInterval(long notBefore, long notAfter) {
        if (notBefore < 0 || (notAfter != 0 && notAfter <= notBefore)) {
            throw new IllegalArgumentException("license interval is invalid");
        }
    }

    /**
     * 将确定性测试向量生成方法限制在包内，避免生产调用方自行指定 GCM nonce。
     */
    static byte[] issueWithNonce(IssuerCredentials credentials, long notBefore, long notAfter, byte[] nonce)
            throws GeneralSecurityException {
        if (credentials == null || nonce == null || nonce.length != Crypto.NONCE_BYTES) {
            throw new IllegalArgumentException("credentials and a 12-byte nonce are required");
        }
        validateInterval(notBefore, notAfter);
        final byte[] appId = FormatSupport.utf8(credentials.getAppId());
        final byte[] plain = ByteBuffer.allocate(4 + appId.length + 8 + 8)
                .putInt(appId.length).put(appId).putLong(notBefore).putLong(notAfter).array();
        final byte[] licenseKey = credentials.getLicenseKey();
        try {
            final byte[] encrypted = Crypto.seal(licenseKey, nonce, AAD, plain);
            return ByteBuffer.allocate(MAGIC.length + nonce.length + encrypted.length)
                    .put(MAGIC).put(nonce).put(encrypted).array();
        } finally {
            Arrays.fill(licenseKey, (byte) 0);
        }
    }
}
