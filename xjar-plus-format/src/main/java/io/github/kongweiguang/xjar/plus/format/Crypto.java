/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 使用 JDK 实现版本 2 唯一加密套件 AES-256-GCM 所需的密码学原语。
 *
 * @author kongweiguang
 */
public final class Crypto {
    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 禁止实例化，确保所有调用方共用已核验的提供方和固定密码参数。
     */
    private Crypto() {
    }

    /**
     * 使用平台 CSPRNG 生成随机值，确保产物密钥和 nonce 不由用户输入推导。
     */
    public static byte[] randomBytes(int length) {
        if (length < 1 || length > 1024 * 1024) {
            throw new IllegalArgumentException("length must be between 1 and 1048576");
        }
        final byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /**
     * 加密一个有界协议值；更大的资源由调用方拆分为独立认证的数据块流式处理。
     */
    public static byte[] seal(byte[] key, byte[] nonce, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        validateAesInputs(key, nonce, aad, plaintext);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        if (aad.length > 0) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(plaintext);
    }

    /**
     * 仅在 GCM 标签验证通过后返回明文，以认证结果作为读取边界。
     */
    public static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] ciphertext)
            throws GeneralSecurityException {
        validateAesInputs(key, nonce, aad, ciphertext);
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        if (aad.length > 0) {
            cipher.updateAAD(aad);
        }
        try {
            return cipher.doFinal(ciphertext);
        } catch (AEADBadTagException e) {
            throw e;
        }
    }

    /**
     * 对严格编码的 UTF-8 密码字节执行 PBKDF2，使 Java 与 Go 对非 ASCII 密码得到一致结果且不做 Unicode 归一化。
     */
    public static byte[] derive(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        validateDeriveInputs(password, salt, iterations);
        final byte[] passwordBytes = encodePassword(password);
        byte[] firstBlock = null;
        byte[] u = null;
        byte[] derived = null;
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(passwordBytes, "HmacSHA256"));
            firstBlock = Arrays.copyOf(salt, salt.length + 4);
            firstBlock[firstBlock.length - 1] = 1;
            u = mac.doFinal(firstBlock);
            derived = u.clone();
            for (int round = 1; round < iterations; round++) {
                final byte[] next = mac.doFinal(u);
                Arrays.fill(u, (byte) 0);
                u = next;
                for (int i = 0; i < derived.length; i++) {
                    derived[i] ^= u[i];
                }
            }
            return derived;
        } catch (GeneralSecurityException | RuntimeException e) {
            if (derived != null) {
                Arrays.fill(derived, (byte) 0);
            }
            throw e;
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            if (u != null) {
                Arrays.fill(u, (byte) 0);
            }
            if (firstBlock != null) {
                Arrays.fill(firstBlock, (byte) 0);
            }
        }
    }

    /**
     * 在此集中固定密钥派生的支持参数，确保所有调用方使用相同的计算成本和 salt 长度。
     */
    private static void validateDeriveInputs(char[] password, byte[] salt, int iterations) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("password must not be empty");
        }
        if (salt == null || salt.length != 16) {
            throw new IllegalArgumentException("salt must contain 16 bytes");
        }
        if (iterations != KeyEnvelope.PBKDF2_ITERATIONS) {
            throw new IllegalArgumentException("version 2 requires exactly 600000 PBKDF2 iterations");
        }
    }

    /**
     * 显式定义密码字节的编码方式，不依赖各提供方对 PBEKeySpec 字符的转换规则。
     */
    private static byte[] encodePassword(char[] password) {
        try {
            final ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(password));
            final byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("password is not valid Unicode", e);
        }
    }

    /**
     * 提前拒绝不符合套件约束的参数，避免 Java 密码提供方静默协商为较弱配置。
     */
    private static void validateAesInputs(byte[] key, byte[] nonce, byte[] aad, byte[] payload) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 key must contain 32 bytes");
        }
        if (nonce == null || nonce.length != NONCE_BYTES) {
            throw new IllegalArgumentException("GCM nonce must contain 12 bytes");
        }
        if (aad == null || payload == null) {
            throw new IllegalArgumentException("AAD and payload must not be null");
        }
    }
}
