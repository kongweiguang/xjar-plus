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
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * 覆盖父加载器优先的资源查找，因为服务发现会绕过类加载器的 findResources 覆盖实现。
 *
 * @author kongweiguang
 */
class ArchiveRuntimeParentResourceTest {
    private static final String ARTIFACT_ID = "00112233-4455-4677-8899-aabbccddeeff";
    private static final String SERVICE_PATH = "META-INF/services/fixture.probe.ProbeService";
    private static final String EXTERNAL_PATH = "external-parent.txt";
    private static final byte[] DATA_KEY = new byte[32];
    private static final byte[] SERVICE_TEXT = "fixture.probe.ProbeProvider\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EXTERNAL_TEXT = "outside protected artifact".getBytes(StandardCharsets.UTF_8);

    /**
     * 使用生产封装格式加密一个服务描述文件，使父加载器返回的原始字节可与文本区分。
     */
    private static byte[] encryptedService() throws Exception {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        ChunkCipher.encrypt(new ByteArrayInputStream(SERVICE_TEXT), output, DATA_KEY, ARTIFACT_ID, SERVICE_PATH,
                SERVICE_TEXT.length, new NonceSequence());
        return output.toByteArray();
    }

    /**
     * 写入仅含一个条目的 JAR，使父子类加载器解析出确定的物理资源 URL。
     */
    private static void writeJar(Path path, String entryName, byte[] bytes) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new JarEntry(entryName));
            output.write(bytes);
            output.closeEntry();
        }
    }

    /**
     * 不依赖 boot 元数据构造认证运行时状态，使测试专注于 ClassLoader 查找语义。
     */
    private static ArchiveRuntime runtime(Path archivePath, EntryMetadata metadata) throws Exception {
        final Constructor<ArchiveRuntime> constructor = ArchiveRuntime.class.getDeclaredConstructor(String.class,
                byte[].class, ArchiveIndex.class, Manifest.class, URL.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ARTIFACT_ID, DATA_KEY.clone(), new ArchiveIndex(Arrays.asList(metadata)),
                new Manifest(), archivePath.toUri().toURL());
    }

    /**
     * 构造认证记录，将描述文件明文绑定到其物理路径和原始字节。
     */
    private static EntryMetadata metadata(String path, byte[] plaintext, boolean encrypted) throws Exception {
        final CRC32 crc = new CRC32();
        crc.update(plaintext);
        return new EntryMetadata(path, encrypted, plaintext.length, crc.getValue(),
                MessageDigest.getInstance("SHA-256").digest(plaintext));
    }

    /**
     * 通过新建的非缓存连接读取，使测试验证相同 URL 契约且不保留 JAR 句柄。
     */
    private static byte[] read(URL resource) throws IOException {
        final URLConnection connection = resource.openConnection();
        connection.setUseCaches(false);
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            return output.toByteArray();
        }
    }

    /**
     * 关闭 JDK 共享的 file:JAR 缓存条目，因为 fixture 会专门验证默认缓存所有权。
     */
    private static void closeCachedArchive(Path archivePath) throws IOException {
        final URL resource = new URL("jar:" + archivePath.toUri().toURL().toExternalForm()
                                     + "!/" + SERVICE_PATH);
        final JarURLConnection connection = (JarURLConnection) resource.openConnection();
        connection.getJarFile().close();
    }

    /**
     * 确保每个公开 URL 上的父级密文只解密一次，且无关父级资源仍由原生机制处理。
     */
    @Test
    void authenticatesParentFirstServiceResourcesWithoutChangingExternalResources(@TempDir final Path tempDir)
            throws Exception {
        final Path protectedJar = tempDir.resolve("protected.jar");
        final Path externalJar = tempDir.resolve("external.jar");
        final byte[] ciphertext = encryptedService();
        writeJar(protectedJar, SERVICE_PATH, ciphertext);
        writeJar(externalJar, EXTERNAL_PATH, EXTERNAL_TEXT);
        final ArchiveRuntime archive = runtime(protectedJar, metadata(SERVICE_PATH, SERVICE_TEXT, true));
        try (URLClassLoader parent = new URLClassLoader(new URL[]{externalJar.toUri().toURL(),
                protectedJar.toUri().toURL()}, null);
             PlainArchiveClassLoader application = new PlainArchiveClassLoader(
                     new URL[]{protectedJar.toUri().toURL()}, parent, archive)) {
            final URL parentCiphertext = parent.getResource(SERVICE_PATH);
            assertArrayEquals(ciphertext, read(parentCiphertext), "fixture parent must expose the raw protected bytes");

            final URL selected = application.getResource(SERVICE_PATH);
            assertNotSame(parentCiphertext, selected, "public lookup must replace the raw parent URL");
            assertArrayEquals(SERVICE_TEXT, read(selected), "single-resource lookup must authenticate the descriptor");

            final Enumeration<URL> serviceUrls = application.getResources(SERVICE_PATH);
            final ArrayList<URL> results = new ArrayList<URL>();
            while (serviceUrls.hasMoreElements()) {
                results.add(serviceUrls.nextElement());
            }
            assertEquals(2, results.size(), "parent and local resource ordering must remain intact");
            for (URL result : results) {
                assertArrayEquals(SERVICE_TEXT, read(result),
                        "already-wrapped local results must not be decrypted a second time");
            }

            final URL externalParentResource = parent.getResource(EXTERNAL_PATH);
            final URL externalResult = application.getResource(EXTERNAL_PATH);
            assertEquals(externalParentResource.toExternalForm(), externalResult.toExternalForm(),
                    "foreign parent resources must not be replaced by this archive's handler");
            assertArrayEquals(EXTERNAL_TEXT, read(externalResult));
        } finally {
            closeCachedArchive(protectedJar);
        }
    }
}
