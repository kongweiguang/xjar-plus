/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 验证明文在 URL 使用方读取部分资源前已完成认证。
 *
 * @author kongweiguang
 */
class ArchiveRuntimePlaintextPreflightTest {
    private static final String ARTIFACT_ID = "00112233-4455-4677-8899-aabbccddeeff";
    private static final byte[] DATA_KEY = new byte[32];
    private static final String ROOT_ENTRY = "config/message.txt";
    private static final String DEPENDENCY_ENTRY = "BOOT-INF/lib/example.jar!/config/message.txt";

    /**
     * 通过私有构造器建立运行时状态，使资源校验测试无需启动 boot 流程。
     */
    private static ArchiveRuntime runtime(Path archivePath, EntryMetadata metadata) throws Exception {
        final Constructor<ArchiveRuntime> constructor = ArchiveRuntime.class.getDeclaredConstructor(String.class,
                byte[].class, ArchiveIndex.class, Manifest.class, URL.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ARTIFACT_ID, DATA_KEY.clone(),
                new ArchiveIndex(Collections.singletonList(metadata)), new Manifest(), archivePath.toUri().toURL());
    }

    /**
     * 写入一个物理条目，因为真实框架加载器最终提供相同的 JarURLConnection 契约。
     */
    private static Path writeJar(Path archivePath, String entryPath, byte[] content) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archivePath))) {
            output.putNextEntry(new JarEntry(entryPath));
            output.write(content);
            output.closeEntry();
        }
        return archivePath;
    }

    /**
     * 验证公开包装 URL 的路径，并禁用缓存以覆盖预校验期间的 JarFile 所有权。
     */
    private static URLConnection wrappedConnection(ArchiveRuntime archive, Path archivePath, String entryPath)
            throws IOException {
        final URL resource = new URL("jar:" + archivePath.toUri().toURL() + "!/" + entryPath);
        final URLConnection connection = archive.wrap(resource, entryPath).openConnection();
        connection.setUseCaches(false);
        return connection;
    }

    /**
     * 关闭 JDK 共享缓存中的 JarFile，使 Windows 能在测试后删除临时 fixture。
     */
    private static void closeCachedArchive(Path archivePath, String entryPath) throws IOException {
        final URL resource = new URL("jar:" + archivePath.toUri().toURL() + "!/" + entryPath);
        final JarURLConnection connection = (JarURLConnection) resource.openConnection();
        connection.getJarFile().close();
    }

    /**
     * 记录修改后的物理归档条目必须完整符合的明文认证信息。
     */
    private static EntryMetadata metadata(String path, byte[] content) throws Exception {
        final CRC32 crc = new CRC32();
        crc.update(content);
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        return new EntryMetadata(path, false, content.length, crc.getValue(), digest);
    }

    /**
     * 在获取数据流时拒绝损坏资源，而不是等调用方读过前缀后才报错。
     */
    @Test
    void rejectsCorruptedRootResourceBeforeReturningFirstByte(@TempDir final Path tempDir) throws Exception {
        final byte[] expected = "authenticated content".getBytes("UTF-8");
        final byte[] corrupted = "tampered resource".getBytes("UTF-8");
        final Path archivePath = writeJar(tempDir.resolve("root.jar"), ROOT_ENTRY, corrupted);
        final EntryMetadata entryMetadata = metadata(ROOT_ENTRY, expected);
        final ArchiveRuntime archive = runtime(archivePath, entryMetadata);
        final URLConnection connection = wrappedConnection(archive, archivePath, ROOT_ENTRY);
        try {
            assertThrows(IOException.class, connection::getInputStream,
                    "corrupt plaintext must fail before a partial reader receives a stream");
        } finally {
            closeCachedArchive(archivePath, ROOT_ENTRY);
        }
    }

    /**
     * 对依赖形式的索引路径执行相同预校验，避免被排除的嵌套资源成为例外。
     */
    @Test
    void validatesDependencyStylePlaintextBeforeReturningFirstByte(@TempDir final Path tempDir) throws Exception {
        final byte[] expected = "dependency resource".getBytes("UTF-8");
        final byte[] corrupted = "altered dependency".getBytes("UTF-8");
        final Path archivePath = writeJar(tempDir.resolve("dependency.jar"), DEPENDENCY_ENTRY, corrupted);
        final EntryMetadata entryMetadata = metadata(DEPENDENCY_ENTRY, expected);
        final ArchiveRuntime archive = runtime(archivePath, entryMetadata);
        final URLConnection connection = wrappedConnection(archive, archivePath, DEPENDENCY_ENTRY);
        try {
            assertThrows(IOException.class, connection::getInputStream,
                    "corrupt dependency plaintext must fail before a partial reader receives a stream");
        } finally {
            closeCachedArchive(archivePath, DEPENDENCY_ENTRY);
        }
    }

    /**
     * 为正确的明文返回已完整预校验的数据流，同时保留普通 URL 数据流语义。
     */
    @Test
    void returnsCorrectPlaintextAfterPreflight(@TempDir final Path tempDir) throws Exception {
        final byte[] expected = "verified resource".getBytes("UTF-8");
        final Path archivePath = writeJar(tempDir.resolve("valid.jar"), ROOT_ENTRY, expected);
        final EntryMetadata entryMetadata = metadata(ROOT_ENTRY, expected);
        final ArchiveRuntime archive = runtime(archivePath, entryMetadata);
        final URLConnection connection = wrappedConnection(archive, archivePath, ROOT_ENTRY);
        try {
            try (InputStream input = connection.getInputStream()) {
                assertEquals(expected[0] & 0xff, input.read());
            }
        } finally {
            closeCachedArchive(archivePath, ROOT_ENTRY);
        }
    }
}
