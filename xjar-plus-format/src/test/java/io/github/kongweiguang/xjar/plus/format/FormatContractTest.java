/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用仅供公开测试的密钥固定 Java/Go 线格式协议及失败行为。
 *
 * @author kongweiguang
 */
class FormatContractTest {
    @TempDir
    private Path tempDir;

    /**
     * 解码固定十六进制测试数据，不依赖平台字符集或整数表示方式。
     */
    private static byte[] hex(String value) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException("hex input must have even length");
        }
        final byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    /**
     * 使用固定缓冲区完整读取测试数据流，以保持测试源码与 Java 8 兼容。
     */
    private static byte[] readAll(java.io.InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    /**
     * 直接读取私有字段，确保测试验证密钥清零本身，而不只是检查公开访问限制。
     */
    private static byte[] retainedSecret(Object owner, String fieldName) throws ReflectiveOperationException {
        final Field field = owner.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (byte[]) field.get(owner);
    }

    /**
     * 使用真实摘要构建元数据，使索引测试覆盖与 core 相同的字段约束。
     */
    private static EntryMetadata metadata(String path, boolean encrypted, byte[] content) throws Exception {
        final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(content);
        return new EntryMetadata(path, encrypted, content.length, crc.getValue(),
                MessageDigest.getInstance("SHA-256").digest(content));
    }

    /**
     * 确认密码封装会认证所有公开 KDF 和头部字段，且绝不输出原始数据密钥。
     */

    /**
     * 加载 UTF-8 测试夹具，确保跨语言身份向量保留精确的 Unicode 字节。
     */
    private Properties vectors() throws IOException {
        final Properties properties = new Properties();
        final java.io.InputStream input = getClass().getResourceAsStream("/vectors-v2.txt");
        assertNotNull(input, "shared vector resource must be packaged");
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    /**
     * 检查固定 AES 和 UTF-8 PBKDF 向量，避免提供方差异或跨语言实现偏离协议。
     */
    @Test
    void cryptoMatchesCrossLanguageVectors() throws Exception {
        final Properties vectors = vectors();
        assertArrayEquals(hex(vectors.getProperty("aes.sealed")), Crypto.seal(
                hex(vectors.getProperty("aes.key")), hex(vectors.getProperty("aes.nonce")),
                hex(vectors.getProperty("aes.aad")), hex(vectors.getProperty("aes.plaintext"))));
        assertArrayEquals(hex(vectors.getProperty("aes.plaintext")), Crypto.open(
                hex(vectors.getProperty("aes.key")), hex(vectors.getProperty("aes.nonce")),
                hex(vectors.getProperty("aes.aad")), hex(vectors.getProperty("aes.sealed"))));
        assertArrayEquals(hex(vectors.getProperty("pbkdf2.key")), Crypto.derive(
                new String(hex(vectors.getProperty("pbkdf2.password.utf8")), StandardCharsets.UTF_8).toCharArray(),
                hex(vectors.getProperty("pbkdf2.salt")),
                Integer.parseInt(vectors.getProperty("pbkdf2.iterations"))));
        assertArrayEquals(hex(vectors.getProperty("pbkdf2.unicode.key")), Crypto.derive(
                new String(hex(vectors.getProperty("pbkdf2.unicode.password.utf8")), StandardCharsets.UTF_8)
                        .toCharArray(),
                hex(vectors.getProperty("pbkdf2.salt")),
                Integer.parseInt(vectors.getProperty("pbkdf2.iterations"))));
    }

    /**
     * 检查密码封装、认证头部篡改拒绝，以及信封实例的资源所有权。
     */
    @Test
    void keyEnvelopeRoundTripsAndRejectsHeaderMutation() throws Exception {
        final char[] password = "口令 🔐".toCharArray();
        final byte[] encoded;
        try (KeyEnvelope created = KeyEnvelope.create(password)) {
            encoded = created.encode();
            assertEquals(100, encoded.length);
            try (KeyEnvelope parsed = KeyEnvelope.parse(encoded)) {
                assertEquals(created.getArtifactId(), parsed.getArtifactId());
                final byte[] createdKey = created.getDataKey();
                final byte[] unwrappedKey = parsed.unwrap(password);
                try {
                    assertArrayEquals(createdKey, unwrappedKey);
                } finally {
                    Arrays.fill(createdKey, (byte) 0);
                    Arrays.fill(unwrappedKey, (byte) 0);
                }
                final char[] wrongPassword = "wrong".toCharArray();
                try {
                    assertThrows(GeneralSecurityException.class, () -> parsed.unwrap(wrongPassword));
                } finally {
                    Arrays.fill(wrongPassword, '\0');
                }
            }
        } finally {
            Arrays.fill(password, '\0');
        }

        final byte[] alteredSalt = encoded.clone();
        alteredSalt[21] ^= 1;
        try (KeyEnvelope altered = KeyEnvelope.parse(alteredSalt)) {
            final char[] wrongPassword = "口令 🔐".toCharArray();
            try {
                assertThrows(GeneralSecurityException.class,
                        () -> altered.unwrap(wrongPassword));
            } finally {
                Arrays.fill(wrongPassword, '\0');
            }
        }
        assertThrows(IllegalArgumentException.class,
                () -> KeyEnvelope.parse(Arrays.copyOf(encoded, encoded.length - 1)));
        try (KeyEnvelope notUnwrapped = KeyEnvelope.parse(encoded)) {
            assertThrows(IllegalStateException.class, notUnwrapped::getDataKey);
        }
    }

    /**
     * 确认 close 会销毁保留的秘密字节并禁止继续执行秘密操作，同时不丢弃线格式数据。
     */
    @Test
    void secretOwnersWipeAndRejectUseAfterClose() throws Exception {
        final IssuerCredentials issuer = IssuerCredentials.create("close-lifecycle");
        final byte[] issuerKey = retainedSecret(issuer, "licenseKey");
        issuer.close();
        issuer.close();
        assertArrayEquals(new byte[Crypto.KEY_BYTES], issuerKey);
        assertThrows(IllegalStateException.class, issuer::getAppId);
        assertThrows(IllegalStateException.class, issuer::getLicenseKey);
        assertThrows(IllegalStateException.class, issuer::encode);
        assertThrows(IllegalStateException.class, () -> LicenseCodec.issue(issuer, Instant.EPOCH, null));

        final char[] password = "secret lifecycle".toCharArray();
        try (KeyEnvelope envelope = KeyEnvelope.create(password)) {
            final byte[] encoded = envelope.encode();
            final String artifactId = envelope.getArtifactId();
            final byte[] envelopeKey = retainedSecret(envelope, "dataKey");
            envelope.close();
            envelope.close();
            assertArrayEquals(new byte[Crypto.KEY_BYTES], envelopeKey);
            assertThrows(IllegalStateException.class, envelope::getDataKey);
            assertThrows(IllegalStateException.class, () -> envelope.unwrap(password));
            assertArrayEquals(encoded, envelope.encode(), "nonsecret wrapping fields remain serializable");
            assertEquals(artifactId, envelope.getArtifactId());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 防止 nonce 计数器回绕，避免在同一产物密钥下重复使用 GCM nonce。
     */
    @Test
    void nonceSequenceExhaustionIsTerminal() {
        final NonceSequence sequence = new NonceSequence(new byte[8], 0xffffffffL);
        final byte[] last = sequence.next();
        assertEquals(0xffffffffL, ByteBuffer.wrap(last, 8, 4).getInt() & 0xffffffffL);
        assertThrows(IllegalStateException.class, sequence::next);
    }

    /**
     * 确认索引不可变、顺序规范，并且在解析前已经完成整体认证。
     */
    @Test
    void indexRoundTripsAndRejectsCiphertextOrIdentityMutation() throws Exception {
        final byte[] key = hex(vectors().getProperty("aes.key"));
        final String artifactId = "10325476-98ba-4cde-8123-456789abcdef";
        final EntryMetadata first = metadata("资源/应用.class", true, new byte[]{1, 2, 3});
        final EntryMetadata second = metadata("BOOT-INF/lib/a.jar!/p/A.class", false, new byte[]{4, 5});
        final ArchiveIndex source = new ArchiveIndex(Arrays.asList(second, first));
        assertThrows(UnsupportedOperationException.class, () -> source.entries().clear());

        final byte[] encoded = IndexCodec.seal(source, key, artifactId,
                new NonceSequence(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, 4));
        final ArchiveIndex decoded = IndexCodec.open(encoded, key, artifactId);
        assertEquals(2, decoded.entries().size());
        assertEquals(first.getPath(), decoded.get(first.getPath()).getPath());
        assertArrayEquals(first.getSha256(), decoded.get(first.getPath()).getSha256());
        assertThrows(GeneralSecurityException.class,
                () -> IndexCodec.open(encoded, key, "10325476-98ba-4cde-8123-456789abcdee"));

        final byte[] altered = encoded.clone();
        altered[altered.length - 1] ^= 1;
        assertThrows(GeneralSecurityException.class, () -> IndexCodec.open(altered, key, artifactId));
    }

    /**
     * 覆盖空数据和多块数据流，以及标签篡改、截断和尾随字节等情况。
     */
    @Test
    void chunkStreamsAuthenticateBeforeReleaseAndCheckFraming() throws Exception {
        final byte[] key = hex(vectors().getProperty("aes.key"));
        final String artifactId = "10325476-98ba-4cde-8123-456789abcdef";
        final byte[] path = "BOOT-INF/classes/p/样例.class".getBytes(StandardCharsets.UTF_8);
        final byte[] plain = new byte[ChunkCipher.CHUNK_SIZE + 37];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i * 31);
        }
        final ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        ChunkCipher.encrypt(new ByteArrayInputStream(plain), encrypted, key, artifactId,
                new String(path, StandardCharsets.UTF_8), plain.length,
                new NonceSequence(new byte[]{8, 7, 6, 5, 4, 3, 2, 1}, 0));
        final byte[] frame = encrypted.toByteArray();
        assertArrayEquals(plain, readAll(ChunkCipher.decryptingStream(
                new ByteArrayInputStream(frame), key, artifactId,
                new String(path, StandardCharsets.UTF_8), plain.length)));
        try (java.io.InputStream skipped = ChunkCipher.decryptingStream(
                new ByteArrayInputStream(frame), key, artifactId,
                new String(path, StandardCharsets.UTF_8), plain.length)) {
            assertEquals(ChunkCipher.CHUNK_SIZE + 5L, skipped.skip(ChunkCipher.CHUNK_SIZE + 5L));
            assertEquals(plain[ChunkCipher.CHUNK_SIZE + 5] & 0xff, skipped.read());
        }

        final byte[] altered = frame.clone();
        altered[ChunkCipher.CHUNK_SIZE + 16 + 4 + 12] ^= 1;
        final ByteArrayOutputStream released = new ByteArrayOutputStream();
        final IOException authFailure = assertThrows(IOException.class, () -> {
            try (java.io.InputStream stream = ChunkCipher.decryptingStream(
                    new ByteArrayInputStream(altered), key, artifactId,
                    new String(path, StandardCharsets.UTF_8), plain.length)) {
                final byte[] buffer = new byte[ChunkCipher.CHUNK_SIZE + 1];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    released.write(buffer, 0, count);
                }
            }
        });
        assertNotNull(authFailure.getCause());
        assertEquals(0, released.size(), "the tampered first block must not release plaintext");

        assertThrows(IOException.class, () -> readAll(ChunkCipher.decryptingStream(
                new ByteArrayInputStream(Arrays.copyOf(frame, frame.length - 1)), key, artifactId,
                new String(path, StandardCharsets.UTF_8), plain.length)));

        final ByteArrayOutputStream emptyFrame = new ByteArrayOutputStream();
        ChunkCipher.encrypt(new ByteArrayInputStream(new byte[0]), emptyFrame, key, artifactId,
                "empty.bin", 0, new NonceSequence(new byte[8], 1));
        assertEquals(0, readAll(ChunkCipher.decryptingStream(
                new ByteArrayInputStream(emptyFrame.toByteArray()), key, artifactId, "empty.bin", 0)).length);
        final byte[] withTail = Arrays.copyOf(emptyFrame.toByteArray(), emptyFrame.size() + 1);
        try (java.io.InputStream trailing = ChunkCipher.decryptingStream(
                new ByteArrayInputStream(withTail), key, artifactId, "empty.bin", 0)) {
            final IOException firstFailure = assertThrows(IOException.class, trailing::read);
            final IOException retryFailure = assertThrows(IOException.class, trailing::read);
            assertSame(firstFailure, retryFailure, "framing failures remain sticky after the first failed read");
            assertThrows(IOException.class, trailing::available);
        }
        assertThrows(IOException.class, () -> ChunkCipher.decryptingStream(
                new ByteArrayInputStream(frame), key, artifactId,
                new String(path, StandardCharsets.UTF_8), plain.length - 1));
    }

    /**
     * 确认 XJI2 和 XJL2 的精确字节表示与 Go 启动器实现一致。
     */
    @Test
    void issuerAndLicenseMatchSharedVectors() throws Exception {
        final Properties vectors = vectors();
        final byte[] issuerBytes = hex(vectors.getProperty("xji2.encoded"));
        final IssuerCredentials issuer = IssuerCredentials.parse(issuerBytes);
        assertEquals(vectors.getProperty("xji2.appId"), issuer.getAppId());
        assertArrayEquals(hex(vectors.getProperty("xji2.licenseKey")), issuer.getLicenseKey());
        assertArrayEquals(issuerBytes, issuer.encode());
        assertThrows(IllegalArgumentException.class,
                () -> IssuerCredentials.parse(Arrays.copyOf(issuerBytes, issuerBytes.length + 1)));

        final String unicodeSpaceAppId = vectors.getProperty("xji2.unicode-space.appId");
        final byte[] unicodeSpaceIssuerBytes = hex(vectors.getProperty("xji2.unicode-space.encoded"));
        final IssuerCredentials unicodeSpaceIssuer = IssuerCredentials.parse(unicodeSpaceIssuerBytes);
        assertEquals(unicodeSpaceAppId, unicodeSpaceIssuer.getAppId());
        assertArrayEquals(unicodeSpaceIssuerBytes, unicodeSpaceIssuer.encode());

        final byte[] nonce = hex(vectors.getProperty("xjl2.nonce"));
        final long start = Long.parseLong(vectors.getProperty("xjl2.notBefore"));
        final byte[] licenseBytes = hex(vectors.getProperty("xjl2.encoded"));
        assertArrayEquals(licenseBytes, LicenseCodec.issueWithNonce(issuer, start, 0, nonce));
        final LicenseGrant grant = LicenseCodec.open(licenseBytes, issuer.getLicenseKey(), issuer.getAppId());
        assertEquals(start, grant.getNotBeforeEpochSecond());
        assertTrue(grant.isPermanent());
        assertTrue(grant.isValidAt(start));
        assertFalse(grant.isValidAt(start - 1));
        assertThrows(GeneralSecurityException.class,
                () -> LicenseCodec.open(licenseBytes, issuer.getLicenseKey(), "different-app"));

        final byte[] unicodeSpaceLicense = hex(vectors.getProperty("xjl2.unicode-space.encoded"));
        assertArrayEquals(unicodeSpaceLicense,
                LicenseCodec.issueWithNonce(unicodeSpaceIssuer, start, 0, nonce));
        assertEquals(unicodeSpaceAppId,
                LicenseCodec.open(unicodeSpaceLicense, unicodeSpaceIssuer.getLicenseKey(), unicodeSpaceAppId)
                        .getAppId());
        assertThrows(GeneralSecurityException.class,
                () -> LicenseCodec.open(unicodeSpaceLicense, unicodeSpaceIssuer.getLicenseKey(), "示例  app"));
    }

    /**
     * 保留空格，仅移除末尾一个换行序列，并拒绝格式错误的 UTF-8。
     */
    @Test
    void passwordFileIsStrictBoundedAndDoesNotTrim() throws Exception {
        final Path passwordFile = tempDir.resolve("password.txt");
        Files.write(passwordFile, "  keep spaces  \r\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("  keep spaces  ", new String(Passwords.readFile(passwordFile)));
        Files.write(passwordFile, "keep\n\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("keep\n", new String(Passwords.readFile(passwordFile)));
        Files.write(passwordFile, new byte[]{(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> Passwords.readFile(passwordFile));
        Files.write(passwordFile, new byte[Passwords.MAX_FILE_BYTES + 1]);
        assertThrows(IOException.class, () -> Passwords.readFile(passwordFile));
    }
}
