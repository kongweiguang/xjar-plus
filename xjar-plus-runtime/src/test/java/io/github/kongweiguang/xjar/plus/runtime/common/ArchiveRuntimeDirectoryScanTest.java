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
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 确保框架包扫描通过认证 URL handler 获取资源，而不是直接使用原始 URL。
 *
 * @author kongweiguang
 */
class ArchiveRuntimeDirectoryScanTest {
    private static final String ARTIFACT_ID = "00112233-4455-4677-8899-aabbccddeeff";
    private static final String ENTRY_PATH = "fixture/scan/ScanTarget.class";
    private static final String SIBLING_PATH = "fixture/scan/ScanSibling.class";
    private static final byte[] KEY = new byte[32];
    private static final byte[] PLAINTEXT = new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe, 1, 2, 3};
    private static final byte[] SIBLING_PLAINTEXT = new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
            9, 8, 7};

    /**
     * 构造与 Spring 目录扫描将相对资源解析为绝对 URL 后相同的 URL 形式。
     */
    private static URL relativeResourceUrl(URL contextUrl, URL archiveDirectoryUrl, String relativePath)
            throws IOException {
        return new URL(contextUrl, archiveDirectoryUrl.toExternalForm() + "/" + relativePath);
    }

    /**
     * 写入一个 GCM 封装的类和一个故意不入索引的同级条目，以验证扫描器的目录视图。
     */
    private static Path writeFixtureArchive(Path archivePath) throws Exception {
        final ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        ChunkCipher.encrypt(new ByteArrayInputStream(PLAINTEXT), encrypted, KEY, ARTIFACT_ID, ENTRY_PATH,
                PLAINTEXT.length, new NonceSequence());
        final ByteArrayOutputStream encryptedSibling = new ByteArrayOutputStream();
        ChunkCipher.encrypt(new ByteArrayInputStream(SIBLING_PLAINTEXT), encryptedSibling, KEY, ARTIFACT_ID,
                SIBLING_PATH, SIBLING_PLAINTEXT.length, new NonceSequence());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archivePath))) {
            writeEntry(output, "fixture/scan/", new byte[0]);
            writeEntry(output, ENTRY_PATH, encrypted.toByteArray());
            writeEntry(output, SIBLING_PATH, encryptedSibling.toByteArray());
            writeEntry(output, "fixture/scan/Unindexed.class", new byte[]{1, 2, 3});
        }
        return archivePath;
    }

    /**
     * 写入确定性的条目字节，且不在专用临时归档之外创建目录。
     */
    private static void writeEntry(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    /**
     * 在向 runtime 提供目录视图前，构造其要求的准确认证元数据。
     */
    private static ArchiveRuntime runtime(Path archivePath) throws Exception {
        final CRC32 crc = new CRC32();
        crc.update(PLAINTEXT);
        final EntryMetadata metadata = new EntryMetadata(ENTRY_PATH, true, PLAINTEXT.length, crc.getValue(),
                MessageDigest.getInstance("SHA-256").digest(PLAINTEXT));
        final CRC32 siblingCrc = new CRC32();
        siblingCrc.update(SIBLING_PLAINTEXT);
        final EntryMetadata siblingMetadata = new EntryMetadata(SIBLING_PATH, true, SIBLING_PLAINTEXT.length,
                siblingCrc.getValue(), MessageDigest.getInstance("SHA-256").digest(SIBLING_PLAINTEXT));
        final Constructor<ArchiveRuntime> constructor = ArchiveRuntime.class.getDeclaredConstructor(String.class,
                byte[].class, ArchiveIndex.class, Manifest.class, URL.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ARTIFACT_ID, Arrays.copyOf(KEY, KEY.length),
                new ArchiveIndex(Arrays.asList(metadata, siblingMetadata)), new Manifest(),
                archivePath.toUri().toURL());
    }

    /**
     * 使用固定大小缓冲区读完整个数据流，模拟扫描器使用方式且不依赖 Java 9 的流辅助 API。
     */
    private static byte[] readFully(InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[256];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > 0) {
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    /**
     * 关闭 JDK 共享 URL 缓存条目，使 Windows 能删除临时 fixture 归档。
     */
    private static void closeCachedArchive(Path archivePath) throws IOException {
        final URL resource = new URL("jar:" + archivePath.toUri().toURL() + "!/" + ENTRY_PATH);
        final JarURLConnection connection = (JarURLConnection) resource.openConnection();
        connection.getJarFile().close();
    }

    /**
     * 验证 Spring 风格的相对 URL 能解密索引类，并拒绝未入索引或外部目标。
     */
    @Test
    void relativePackageScanReadsAuthenticatedBytesAndRejectsUnindexedFiles(@TempDir final Path tempDir)
            throws Exception {
        final Path archivePath = writeFixtureArchive(tempDir.resolve("scan.jar"));
        final ArchiveRuntime archive = runtime(archivePath);
        try {
            final URL directoryUrl = new URL("jar:" + archivePath.toUri().toURL().toExternalForm()
                                             + "!/fixture/scan");
            final URL wrappedUrl = archive.wrap(directoryUrl, "fixture/scan");
            assertNotSame(directoryUrl, wrappedUrl, "indexed package directories need an authenticated scan view");
            assertSame(wrappedUrl, archive.wrap(wrappedUrl, "fixture/scan"),
                    "re-wrapping a scan directory must preserve its native handler without probing a fake entry");

            final JarURLConnection connection = (JarURLConnection) wrappedUrl.openConnection();
            connection.setUseCaches(false);
            try (JarFile jarFile = connection.getJarFile()) {
                assertTrue(jarFile.getJarEntry(ENTRY_PATH) != null,
                        "the native archive handler must retain entry enumeration for framework scanners");
            }
            final URL classUrl = relativeResourceUrl(wrappedUrl, wrappedUrl, "ScanTarget.class");
            assertSame(classUrl, archive.wrap(classUrl, ENTRY_PATH),
                    "re-wrapping a relative file URL must not install a second decrypting layer");
            final JarURLConnection classConnection = (JarURLConnection) classUrl.openConnection();
            classConnection.setUseCaches(false);
            try (InputStream input = classConnection.getInputStream()) {
                assertArrayEquals(PLAINTEXT, readFully(input));
            }
            final URL siblingUrl = relativeResourceUrl(classUrl, wrappedUrl, "ScanSibling.class");
            final JarURLConnection siblingConnection = (JarURLConnection) siblingUrl.openConnection();
            siblingConnection.setUseCaches(false);
            try (InputStream input = siblingConnection.getInputStream()) {
                assertArrayEquals(SIBLING_PLAINTEXT, readFully(input),
                        "an inherited file handler must resolve the selected sibling entry");
            }
            final URL unindexedUrl = relativeResourceUrl(classUrl, wrappedUrl, "Unindexed.class");
            assertThrows(IOException.class, () -> unindexedUrl.openConnection().getInputStream(),
                    "relative scans must not fall back to ciphertext for entries absent from the index");
            final URL missingUrl = relativeResourceUrl(classUrl, wrappedUrl, "Missing.class");
            assertThrows(IOException.class, () -> missingUrl.openConnection(),
                    "relative scans must reject missing physical entries instead of returning the context entry");
            final URL foreignUrl = new URL(classUrl, "jar:file:/unrelated.jar!/fixture/scan/Other.class");
            assertThrows(IOException.class, () -> foreignUrl.openConnection(),
                    "a directory handler must not authenticate resources from a foreign archive");
        } finally {
            closeCachedArchive(archivePath);
        }
    }
}
