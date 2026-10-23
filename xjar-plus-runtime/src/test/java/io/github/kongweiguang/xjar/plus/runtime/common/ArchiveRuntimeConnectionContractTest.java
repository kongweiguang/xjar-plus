/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.ChunkCipher;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import io.github.kongweiguang.xjar.plus.format.NonceSequence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保留 HTTP 和 SPI 使用方依赖的 URLConnection 资源契约，确保其跨 JAR 运行时一致。
 *
 * @author kongweiguang
 */
class ArchiveRuntimeConnectionContractTest {
    private static final String ARTIFACT_ID = "00112233-4455-4677-8899-aabbccddeeff";
    private static final byte[] DATA_KEY = new byte[32];
    private static final String ENTRY_PATH = "static/payload.bin";

    /**
     * 创建新的认证运行时视图，使 URL 契约单测不依赖密钥信封启动流程。
     */
    private static ArchiveRuntime runtime(Path archivePath, EntryMetadata metadata) throws Exception {
        final Constructor<ArchiveRuntime> constructor = ArchiveRuntime.class.getDeclaredConstructor(String.class,
                byte[].class, ArchiveIndex.class, Manifest.class, URL.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ARTIFACT_ID, DATA_KEY.clone(),
                new ArchiveIndex(Collections.singletonList(metadata)), new Manifest(), archivePath.toUri().toURL());
    }

    /**
     * 使用相同的解密 URL handler，但不经过 ArchiveRuntime 发现探测，以单独验证响应句柄的清理。
     */
    private static ConnectionFixture fixture(Path archivePath, byte[] plaintext, boolean corrupt) throws Exception {
        return fixture(archivePath, plaintext, corrupt, plaintext.length);
    }

    /**
     * 允许仅测试元数据边界时模拟大于 URLConnection int API 上限的已认证资源。
     */
    private static ConnectionFixture fixture(Path archivePath, byte[] plaintext, boolean corrupt,
                                             long declaredLength) throws Exception {
        final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ChunkCipher.encrypt(new ByteArrayInputStream(plaintext), encoded, DATA_KEY, ARTIFACT_ID,
                ENTRY_PATH, plaintext.length, new NonceSequence());
        final byte[] ciphertext = encoded.toByteArray();
        if (corrupt) {
            ciphertext[ciphertext.length - 1] ^= 1;
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archivePath))) {
            output.putNextEntry(new JarEntry(ENTRY_PATH));
            output.write(ciphertext);
            output.closeEntry();
        }
        final EntryMetadata metadata = metadata(plaintext, declaredLength);
        final ArchiveRuntime archive = runtime(archivePath, metadata);
        final URL raw = new URL("jar:" + archivePath.toUri().toASCIIString() + "!/" + ENTRY_PATH);
        final URL wrapped = new URL(raw.getProtocol(), raw.getHost(), raw.getPort(), raw.getFile(),
                new DecryptingUrlHandler(archive, raw, metadata));
        final URLConnection connection = wrapped.openConnection();
        if (!(connection instanceof JarURLConnection)) {
            throw new IOException("Wrapped archive resource is not a JAR connection");
        }
        final JarURLConnection jarConnection = (JarURLConnection) connection;
        jarConnection.setUseCaches(false);
        return new ConnectionFixture(raw, jarConnection, ciphertext);
    }

    /**
     * 通过多个 URLConnection API 检查 header 和日期访问；否则这些 delegate 路径可能在归档 JarFile 关闭后仍泄漏 JDK 管理的文件数据流。
     */
    private static void assertLengthHeaders(URLConnection connection, long expected) {
        for (String name : new String[]{"content-length", "Content-Length", "CONTENT-LENGTH"}) {
            assertEquals(Long.toString(expected), connection.getHeaderField(name));
            if (expected <= Integer.MAX_VALUE) {
                assertEquals((int) expected, connection.getHeaderFieldInt(name, -123));
            }
            assertEquals(expected, connection.getHeaderFieldLong(name, -123L));
        }

        boolean foundInMap = false;
        for (java.util.Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if ("content-length".equalsIgnoreCase(header.getKey())) {
                foundInMap = true;
                assertEquals(Collections.singletonList(Long.toString(expected)), header.getValue());
            }
        }
        assertTrue(foundInMap, "header map must expose the authenticated plaintext length");
        assertNull(connection.getHeaderField("x-no-such-header"));
        assertNull(connection.getContentEncoding());
        assertEquals(0L, connection.getDate());
        assertEquals(0L, connection.getExpiration());
        assertEquals(-123L, connection.getHeaderFieldDate("x-no-such-header", -123L));

        boolean foundByIndex = false;
        for (int index = 0; index < 64; index++) {
            final String name = connection.getHeaderFieldKey(index);
            final String value = connection.getHeaderField(index);
            if (name == null && value == null) {
                break;
            }
            if ("content-length".equalsIgnoreCase(name)) {
                foundByIndex = true;
                assertEquals(Long.toString(expected), value);
            }
        }
        assertTrue(foundByIndex, "indexed headers must expose the authenticated plaintext length");
        assertEquals(-123, connection.getHeaderFieldInt("x-no-such-header", -123));
        assertEquals(-123L, connection.getHeaderFieldLong("x-no-such-header", -123L));
    }

    /**
     * 构造确定性的二进制内容，避免偏移和 CRC 断言受字符转换影响。
     */
    private static byte[] binary(int size) {
        final byte[] bytes = new byte[size];
        for (int index = 0; index < size; index++) {
            bytes[index] = (byte) (index * 31 + 17);
        }
        return bytes;
    }

    /**
     * 计算与 ZIP 一致的原始 CRC，供脱离式 JarEntry 暴露给资源使用方。
     */
    private static long crc(byte[] bytes) {
        final CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    /**
     * 测试元数据 API 时单独设置认证长度，无需读取 fixture 正文。
     */
    private static EntryMetadata metadata(byte[] plaintext, long declaredLength) throws Exception {
        return new EntryMetadata(ENTRY_PATH, true, declaredLength, crc(plaintext),
                MessageDigest.getInstance("SHA-256").digest(plaintext));
    }

    /**
     * 仅当 JAR 协议正确选择输入流 handler 时，才转换 content handler 的结果。
     */
    private static InputStream contentStream(Object content) {
        if (!(content instanceof InputStream)) {
            throw new AssertionError("JAR content handler returned "
                                     + (content == null ? "null" : content.getClass().getName()));
        }
        return (InputStream) content;
    }

    /**
     * 完整读取数据流以进行相等性检查，同时兼容 runtime 的 Java 8 字节码目标。
     */
    private static byte[] read(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /**
     * 复制指定的明文后缀，不使用 Java 8 目标运行时不具备的新版 JDK 数组辅助 API。
     */
    private static byte[] slice(byte[] source, int offset) {
        final byte[] result = new byte[source.length - offset];
        System.arraycopy(source, offset, result, 0, result.length);
        return result;
    }

    /**
     * 确保所有公开长度和条目元数据视图返回已认证明文信息，而非 GCM 封装长度。
     */
    @Test
    void plaintextLengthsHeadersAndEntryCrcRemainConsistent(@TempDir final Path tempDir) throws Exception {
        for (int size : new int[]{0, 15, 16, 17, 1024 * 1024 + 37}) {
            final byte[] plaintext = binary(size);
            try (ConnectionFixture fixture = fixture(tempDir.resolve("length-" + size + ".jar"), plaintext, false)) {
                final JarURLConnection connection = fixture.connection();
                assertEquals(size, connection.getContentLength());
                assertEquals((long) size, connection.getContentLengthLong());
                assertLengthHeaders(connection, size);

                final JarEntry exposed = connection.getJarEntry();
                assertEquals(size, exposed.getSize());
                assertEquals(crc(plaintext), exposed.getCrc());
                assertArrayEquals(plaintext, read(connection.getInputStream()));

                final JarEntry stored = fixture.physicalEntry();
                assertNotEquals(stored.getSize(), exposed.getSize());
                assertNotEquals(stored.getCrc(), exposed.getCrc());
            }
        }
    }

    /**
     * 保留 URLConnection 的 int 溢出哨兵值，同时在 header 中提供准确的 long 型认证长度。
     */
    @Test
    void reportsUnknownIntLengthWhenPlaintextExceedsIntegerRange(@TempDir final Path tempDir) throws Exception {
        final long declaredLength = (long) Integer.MAX_VALUE + 1L;
        try (ConnectionFixture fixture = fixture(tempDir.resolve("large-metadata.jar"), binary(1), false,
                declaredLength)) {
            final JarURLConnection connection = fixture.connection();
            assertEquals(-1, connection.getContentLength());
            assertEquals(declaredLength, connection.getContentLengthLong());
            assertEquals(-123, connection.getHeaderFieldInt("Content-Length", -123));
            assertEquals(declaredLength, connection.getHeaderFieldLong("Content-Length", -123L));
            assertLengthHeaders(connection, declaredLength);
            assertEquals(declaredLength, connection.getJarEntry().getSize());
        }
    }

    /**
     * 即使元数据查询已连接非缓存 URL，重复读取和 content handler 访问仍相互独立。
     */
    @Test
    void metadataAndContentAccessDoNotConsumeRepeatedStreams(@TempDir final Path tempDir) throws Exception {
        final byte[] plaintext = binary(8193);
        try (ConnectionFixture fixture = fixture(tempDir.resolve("repeat.jar"), plaintext, false)) {
            final JarURLConnection connection = fixture.connection();
            connection.setUseCaches(true);
            assertTrue(connection.getUseCaches());
            connection.setUseCaches(false);
            assertFalse(connection.getUseCaches());

            assertLengthHeaders(connection, plaintext.length);
            assertEquals(crc(plaintext), connection.getJarEntry().getCrc());
            assertArrayEquals(plaintext, read(connection.getInputStream()));
            assertArrayEquals(plaintext, read(connection.getInputStream()));
            assertArrayEquals(plaintext, read(contentStream(connection.getContent())));
            assertArrayEquals(plaintext, read(contentStream(
                    connection.getContent(new Class<?>[]{InputStream.class}))));
            assertEquals((long) plaintext.length, connection.getContentLengthLong());
        }
    }

    /**
     * 检查 JDK 共享 JarFile 缓存，并由 fixture 持有显式句柄以便确定性清理。
     */
    @Test
    void cachedResponsesRemainReusableUntilFixtureClosesTheCacheHandle(@TempDir final Path tempDir) throws Exception {
        final byte[] plaintext = binary(4097);
        try (ConnectionFixture fixture = fixture(tempDir.resolve("cached.jar"), plaintext, false)) {
            final JarURLConnection connection = fixture.connection();
            connection.setUseCaches(true);
            assertTrue(connection.getUseCaches());
            assertArrayEquals(plaintext, read(connection.getInputStream()));
            assertArrayEquals(plaintext, read(connection.getInputStream()));
            assertEquals(crc(plaintext), connection.getJarEntry().getCrc());
        }
    }

    /**
     * 验证跨越 GCM 分块边界时的 skip 语义，确保 Range 读取落在准确的明文偏移处。
     */
    @Test
    void skipsUsePlaintextOffsetsAndPreserveEofSemantics(@TempDir final Path tempDir) throws Exception {
        final byte[] plaintext = binary(1024 * 1024 + 37);
        try (ConnectionFixture fixture = fixture(tempDir.resolve("skip.jar"), plaintext, false);
             InputStream input = fixture.connection().getInputStream()) {
            final int offset = 1024 * 1024 + 5;
            assertEquals(0L, input.skip(-1L));
            assertEquals((long) offset, input.skip(offset));
            assertArrayEquals(slice(plaintext, offset), read(input));
        }

        try (ConnectionFixture fixture = fixture(tempDir.resolve("skip-past-eof.jar"), binary(17), false);
             InputStream input = fixture.connection().getInputStream()) {
            assertEquals(17L, input.skip(100L));
            assertEquals(-1, input.read());
            assertEquals(0L, input.skip(1L));
            assertEquals(-1, input.read());
        }
    }

    /**
     * 确保并发元数据查询和独立数据流不会共享可变解密游标。
     */
    @Test
    void concurrentMetadataAndReadsRemainConsistent(@TempDir final Path tempDir) throws Exception {
        final byte[] plaintext = binary(256 * 1024 + 3);
        try (ConnectionFixture fixture = fixture(tempDir.resolve("concurrent.jar"), plaintext, false)) {
            final JarURLConnection connection = fixture.connection();
            connection.setUseCaches(false);
            assertFalse(connection.getUseCaches());
            final ExecutorService workers = Executors.newFixedThreadPool(6);
            try {
                final List<Callable<Void>> requests = new ArrayList<Callable<Void>>();
                for (int index = 0; index < 18; index++) {
                    requests.add(() -> {
                        assertEquals((long) plaintext.length, connection.getContentLengthLong());
                        assertEquals(crc(plaintext), connection.getJarEntry().getCrc());
                        assertLengthHeaders(connection, plaintext.length);
                        assertArrayEquals(plaintext, read(connection.getInputStream()));
                        return null;
                    });
                }
                for (Future<Void> result : workers.invokeAll(requests)) {
                    result.get();
                }
            } finally {
                workers.shutdownNow();
            }
        }
    }

    /**
     * 在向调用方返回明文前拒绝已损坏的首个 GCM 分块。
     */
    @Test
    void corruptedCiphertextKeepsPlaintextLengthButExposesNoBytes(@TempDir final Path tempDir) throws Exception {
        final byte[] plaintext = binary(37);
        try (ConnectionFixture fixture = fixture(tempDir.resolve("corrupted.jar"), plaintext, true)) {
            final JarURLConnection connection = fixture.connection();
            assertEquals((long) plaintext.length, connection.getContentLengthLong());
            assertLengthHeaders(connection, plaintext.length);
            assertNotEquals(plaintext.length, fixture.ciphertext().length);

            try (InputStream input = contentStream(connection.getInputStream())) {
                assertThrows(IOException.class, input::read);
            }
            assertThrows(IOException.class, () -> read(contentStream(connection.getContent())));
            assertEquals((long) plaintext.length, connection.getContentLengthLong());
        }
    }

    /**
     * 持有用于对比 URL 可见元数据与 ZIP 存储元数据的连接和密文信息。
     */
    private static final class ConnectionFixture implements AutoCloseable {
        private final URL rawUrl;
        private final JarURLConnection connection;
        private final byte[] ciphertext;

        /**
     * 单独保留物理 URL，以检查合成元数据不会修改源 JAR。
         */
        private ConnectionFixture(URL rawUrl, JarURLConnection connection, byte[] ciphertext) {
            this.rawUrl = rawUrl;
            this.connection = connection;
            this.ciphertext = ciphertext.clone();
        }

        /**
     * 返回供 URLConnection 使用方调用的公开包装器。
         */
        private JarURLConnection connection() {
            return connection;
        }

        /**
     * 返回副本，避免损坏数据断言修改 fixture 中保存的字节。
         */
        private byte[] ciphertext() {
            return ciphertext.clone();
        }

        /**
     * 读取底层 ZIP 条目，但不修改包装器公开的脱离式元数据。
         */
        private JarEntry physicalEntry() throws IOException {
            final JarURLConnection source = (JarURLConnection) rawUrl.openConnection();
            source.setUseCaches(false);
            try {
                return source.getJarEntry();
            } finally {
                source.getJarFile().close();
            }
        }

        /**
     * 关闭 fixture 的非缓存句柄及对应 JDK 缓存条目，使 Windows 能删除该归档。
         */
        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                connection.getJarFile().close();
            } catch (IOException closeFailure) {
                failure = closeFailure;
            }
            try {
                final JarURLConnection source = (JarURLConnection) rawUrl.openConnection();
                source.getJarFile().close();
            } catch (IOException closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

    }
}
