/* @author kongweiguang */
package io.github.kongweiguang.xjar.plus.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

/**
 * 独立执行协议审计，不读取或写入任何业务密钥。
 */
public final class FormatSecurityProbe {
    private static final String ARTIFACT = "00112233-4455-4677-8899-aabbccddeeff";
    private static final byte[] TEST_KEY = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
    private static int checks;

    /**
     * 保持探针可独立执行，避免依赖尚未完成的 Maven 聚合构建。
     */
    private FormatSecurityProbe() {
    }

    /**
     * 直接对当前源码执行对抗用例，只导出公开测试向量生成的许可证。
     */
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]);
        Properties vectors = new Properties();
        try (Reader reader = Files.newBufferedReader(root.resolve("xjar-plus-format/src/test/resources/vectors-v2.txt"), StandardCharsets.UTF_8)) {
            vectors.load(reader);
        }
        verifyVectors(vectors);
        verifyNonceExhaustion();
        verifyEnvelope();
        verifyIndex();
        verifyChunks();
        exportLicense(root.resolve("integration-tests/security/target"));
        System.out.println("FORMAT_SECURITY_PROBE_OK checks=" + checks);
    }

    /**
     * 对照已固定的 ASCII 与非 BMP Unicode 派生向量，避免仅靠自身往返掩盖互操作错误。
     */
    private static void verifyVectors(Properties vectors) throws Exception {
        for (String name : new String[]{"pbkdf2", "pbkdf2.unicode"}) {
            char[] password = new String(hex(vectors.getProperty(name + ".password.utf8")), StandardCharsets.UTF_8).toCharArray();
            byte[] actual = Crypto.derive(password, hex(vectors.getProperty("pbkdf2.salt")), 600000);
            require(Arrays.equals(actual, hex(vectors.getProperty(name + ".key"))), "PBKDF2 vector " + name);
        }
        require(Arrays.equals(Crypto.seal(TEST_KEY, hex(vectors.getProperty("aes.nonce")), hex(vectors.getProperty("aes.aad")), hex(vectors.getProperty("aes.plaintext"))), hex(vectors.getProperty("aes.sealed"))), "AES-GCM vector");
        expectFailure(() -> Crypto.derive(new char[]{'\ud800'}, new byte[16], 600000), "unpaired surrogate");
        expectFailure(() -> Crypto.derive(new char[]{'a'}, new byte[16], Integer.MAX_VALUE), "attacker-selected KDF cost");
    }

    /**
     * 直接触达 uint32 边界，避免必须调用数十亿次才能发现 nonce 回绕。
     */
    private static void verifyNonceExhaustion() throws Exception {
        NonceSequence sequence = new NonceSequence(new byte[8], 0xfffffffeL);
        require(ByteBuffer.wrap(sequence.next(), 8, 4).getInt() == -2, "penultimate nonce");
        require(ByteBuffer.wrap(sequence.next(), 8, 4).getInt() == -1, "last nonce");
        expectFailure(sequence::next, "nonce wrap");
        expectFailure(sequence::next, "nonce failure remains exhausted");
    }

    /**
     * 公开信封字段损坏时必须拒绝，不能经由降级路径继续产生密钥。
     */
    private static void verifyEnvelope() throws Exception {
        char[] password = "口令 🔐".toCharArray();
        KeyEnvelope created = KeyEnvelope.create(password);
        byte[] encoded = created.encode();
        require(encoded.length == 100, "fixed envelope length");
        require(Arrays.equals(created.getDataKey(), KeyEnvelope.parse(encoded).unwrap(password)), "envelope unwrap");
        expectFailure(() -> KeyEnvelope.parse(encoded).getDataKey(), "parsed envelope has no clear key");
        for (int location : new int[]{0, 4, 20, 36, 40, 99}) {
            final byte[] tampered = encoded.clone();
            tampered[location] ^= 1;
            expectFailure(() -> KeyEnvelope.parse(tampered).unwrap(password), "envelope tamper " + location);
        }
        expectFailure(() -> KeyEnvelope.parse(Arrays.copyOf(encoded, encoded.length + 1)), "envelope trailing byte");
        KeyEnvelope fresh = KeyEnvelope.create(password);
        require(!created.getArtifactId().equals(fresh.getArtifactId()) && !Arrays.equals(created.getDataKey(), fresh.getDataKey()), "fresh artifact key and identity");
    }

    /**
     * 先认证再暴露索引条目；即使认证通过，也拒绝结构非法的索引明文。
     */
    private static void verifyIndex() throws Exception {
        EntryMetadata entry = new EntryMetadata("目录/a.class", true, 0L, 0L, new byte[32]);
        byte[] encoded = IndexCodec.seal(new ArchiveIndex(Collections.singletonList(entry)), TEST_KEY, ARTIFACT, new NonceSequence());
        require(IndexCodec.open(encoded, TEST_KEY, ARTIFACT).entries().size() == 1, "index round trip");
        byte[] tampered = encoded.clone();
        tampered[tampered.length - 1] ^= 1;
        expectFailure(() -> IndexCodec.open(tampered, TEST_KEY, ARTIFACT), "index tag tamper");
        expectFailure(() -> IndexCodec.open(encoded, TEST_KEY, "00112233-4455-4677-8899-aabbccddee00"), "index artifact substitution");
        expectFailure(() -> IndexCodec.open(new byte[0], TEST_KEY, ARTIFACT), "missing index");
        byte[] hugeCount = ByteBuffer.allocate(4).putInt(1000001).array();
        byte[] aad = ByteBuffer.allocate(20).put("XJX2".getBytes(StandardCharsets.US_ASCII)).put(FormatSupport.artifactIdBytes(ARTIFACT)).array();
        byte[] nonce = new byte[12];
        byte[] ciphertext = Crypto.seal(TEST_KEY, nonce, aad, hugeCount);
        byte[] invalidIndex = ByteBuffer.allocate(16 + ciphertext.length).put("XJX2".getBytes(StandardCharsets.US_ASCII)).put(nonce).put(ciphertext).array();
        expectFailure(() -> IndexCodec.open(invalidIndex, TEST_KEY, ARTIFACT), "oversize index count");
    }

    /**
     * 固定身份、分帧、截断与分块顺序约束，首块认证失败时不得交付任何明文。
     */
    private static void verifyChunks() throws Exception {
        byte[] plain = new byte[2 * ChunkCipher.CHUNK_SIZE + 17];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (i * 31);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ChunkCipher.encrypt(new ByteArrayInputStream(plain), output, TEST_KEY, ARTIFACT, "a.bin", plain.length, new NonceSequence());
        byte[] encoded = output.toByteArray();
        require(Arrays.equals(readEntry(encoded, "a.bin", plain.length), plain), "multi-chunk round trip");
        expectFailure(() -> readEntry(encoded, "b.bin", plain.length), "chunk path substitution");
        expectFailure(() -> readEntry(encoded, "a.bin", plain.length - 1), "chunk total length substitution");
        expectFailure(() -> readEntry(Arrays.copyOf(encoded, encoded.length - 1), "a.bin", plain.length), "chunk truncation");
        expectFailure(() -> readEntry(Arrays.copyOf(encoded, encoded.length + 1), "a.bin", plain.length), "chunk trailing bytes");
        byte[] corrupt = encoded.clone();
        corrupt[16 + 4 + 12] ^= 1;
        try (InputStream input = ChunkCipher.decryptingStream(new ByteArrayInputStream(corrupt), TEST_KEY, ARTIFACT, "a.bin", plain.length)) {
            byte[] untouched = new byte[16];
            Arrays.fill(untouched, (byte) 0x55);
            expectFailure(() -> input.read(untouched), "bad first block");
            for (byte value : untouched) {
                require(value == 0x55, "no unauthenticated bytes released");
            }
        }
        int recordLength = 4 + 12 + ChunkCipher.CHUNK_SIZE + 16;
        byte[] reordered = encoded.clone();
        System.arraycopy(encoded, 16 + recordLength, reordered, 16, recordLength);
        System.arraycopy(encoded, 16, reordered, 16 + recordLength, recordLength);
        expectFailure(() -> readEntry(reordered, "a.bin", plain.length), "chunk reordering");
        output.reset();
        ChunkCipher.encrypt(new ByteArrayInputStream(new byte[0]), output, TEST_KEY, ARTIFACT, "empty", 0L, new NonceSequence());
        require(readEntry(output.toByteArray(), "empty", 0L).length == 0, "empty entry authenticates");
        byte[] emptyCorrupt = output.toByteArray();
        emptyCorrupt[emptyCorrupt.length - 1] ^= 1;
        expectFailure(() -> readEntry(emptyCorrupt, "empty", 0L), "empty entry tag tamper");
        byte[] appendedEmpty = Arrays.copyOf(output.toByteArray(), output.size() + 1);
        try (InputStream input = ChunkCipher.decryptingStream(new ByteArrayInputStream(appendedEmpty), TEST_KEY, ARTIFACT, "empty", 0L)) {
            expectFailure(input::read, "empty entry trailing byte");
            expectFailure(input::read, "terminal framing failure must remain failed");
        }
        expectFailure(() -> ChunkCipher.decryptingStream(new ByteArrayInputStream(new byte[0]), TEST_KEY, ARTIFACT, "a.bin", Long.MAX_VALUE), "entry block count overflow");
    }

    /**
     * 仅用公开测试密钥签发许可证，供独立的 Go 生产解码器验证跨语言契约。
     */
    private static void exportLicense(Path directory) throws Exception {
        String appId = "测试应用🔐";
        byte[] appIdBytes = appId.getBytes(StandardCharsets.UTF_8);
        byte[] encodedIssuer = ByteBuffer.allocate(8 + appIdBytes.length + 32).put("XJI2".getBytes(StandardCharsets.US_ASCII)).putInt(appIdBytes.length).put(appIdBytes).put(TEST_KEY).array();
        IssuerCredentials issuer = IssuerCredentials.parse(encodedIssuer);
        byte[] license = LicenseCodec.issue(issuer, Instant.ofEpochSecond(100), Instant.ofEpochSecond(200));
        require(LicenseCodec.open(license, TEST_KEY, appId).isValidAt(100), "license inclusive start");
        require(!LicenseCodec.open(license, TEST_KEY, appId).isValidAt(200), "license exclusive end");
        expectFailure(() -> LicenseCodec.open(license, TEST_KEY, "另一应用🔐"), "license foreign app");
        Files.createDirectories(directory);
        Files.write(directory.resolve("java-license.bin"), license);
    }

    /**
     * 经公开流接口读取，使探针观察到的接受行为与真实类加载器保持一致。
     */
    private static byte[] readEntry(byte[] encoded, String path, long length) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = ChunkCipher.decryptingStream(new ByteArrayInputStream(encoded), TEST_KEY, ARTIFACT, path, length)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    /**
     * 逐项记录成功证据，遇到首个协议约束不符时立即失败。
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
        checks++;
    }

    /**
     * 只接受预期的协议异常，避免探针自身的编程错误被当成安全校验成功。
     */
    private static void expectFailure(Checked action, String message) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException | IOException | GeneralSecurityException expected) {
            checks++;
            return;
        }
        throw new AssertionError("accepted " + message);
    }

    /**
     * 不用 Java 17 的 HexFormat 解码公开常量，以保留实际 Java 8 执行覆盖。
     */
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    private interface Checked {
        /**
         * 保留生产编解码器抛出的受检异常，避免安全断言改写真实失败类型。
         */
        void run() throws Exception;
    }
}
