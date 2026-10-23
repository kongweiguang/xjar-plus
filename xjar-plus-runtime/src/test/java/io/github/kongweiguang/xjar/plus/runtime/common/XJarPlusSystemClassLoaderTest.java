/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖一次性 bridge 契约，适用于不继承启动线程 TCCL 的 JDK 工作线程。
 *
 * @author kongweiguang
 */
class XJarPlusSystemClassLoaderTest {
    private static final String ARTIFACT_ID = "00112233-4455-4677-8899-aabbccddeeff";
    private static final String RESOURCE_PATH = "bridge/probe.txt";
    private static final byte[] DATA_KEY = new byte[32];
    private static final byte[] RESOURCE_BYTES = "authenticated-resource".getBytes(StandardCharsets.UTF_8);

    /**
     * 构建索引中的明文条目，使 bridge 测试验证与生产启动相同的完整性契约。
     */
    private static ArchiveRuntime archive(Path archivePath) throws Exception {
        final CRC32 crc = new CRC32();
        crc.update(RESOURCE_BYTES);
        final EntryMetadata metadata = new EntryMetadata(RESOURCE_PATH, false, RESOURCE_BYTES.length, crc.getValue(),
                MessageDigest.getInstance("SHA-256").digest(RESOURCE_BYTES));
        final Constructor<ArchiveRuntime> constructor = ArchiveRuntime.class.getDeclaredConstructor(String.class,
                byte[].class, ArchiveIndex.class, Manifest.class, URL.class);
        constructor.setAccessible(true);
        final ArchiveIndex index = new ArchiveIndex(Collections.singletonList(metadata));
        return constructor.newInstance(ARTIFACT_ID, DATA_KEY.clone(), index, new Manifest(),
                archivePath.toUri().toURL());
    }

    /**
     * 仅将固定的公开测试资源写入当前测试方法拥有的临时 fixture JAR。
     */
    private static Path writeArchive(Path archivePath) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archivePath))) {
            output.putNextEntry(new JarEntry(RESOURCE_PATH));
            output.write(RESOURCE_BYTES);
            output.closeEntry();
        }
        return archivePath;
    }

    /**
     * 读完并关闭测试创建的 URL 数据流，避免归档句柄逃出 bridge 断言的作用域。
     */
    private static byte[] read(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[128];
            int count;
            while ((count = source.read(buffer)) != -1) {
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            return output.toByteArray();
        }
    }

    /**
     * 关闭 JDK 共享 JAR URL 缓存条目，使 Windows 能删除测试拥有的临时归档。
     */
    private static void closeCachedArchive(Path archivePath) throws IOException {
        final URL entryUrl = new URL("jar:" + archivePath.toUri().toURL() + "!/" + RESOURCE_PATH);
        final JarURLConnection connection = (JarURLConnection) entryUrl.openConnection();
        final JarFile cached = connection.getJarFile();
        cached.close();
    }

    /**
     * 验证 runtime 标识共享、认证资源查找、资源枚举及仅绑定一次的约束。
     */
    @Test
    void routesSystemLookupsThroughTheBoundArchiveLoader(@TempDir final Path tempDirectory) throws Exception {
        final Path archivePath = writeArchive(tempDirectory.resolve("bridge-fixture.jar"));
        final XJarPlusSystemClassLoader bridge = new XJarPlusSystemClassLoader(getClass().getClassLoader());
        final PlainArchiveClassLoader application = new PlainArchiveClassLoader(
                new URL[]{archivePath.toUri().toURL()}, bridge.applicationParent(), archive(archivePath));
        try {
            assertSame(XJarPlusSystemClassLoader.class, bridge.loadClass(XJarPlusSystemClassLoader.class.getName()),
                    "runtime classes must keep the launcher loader's class identity before and after binding");
            bridge.bind(application);
            assertSame(XJarPlusSystemClassLoader.class, bridge.loadClass(XJarPlusSystemClassLoader.class.getName()),
                    "runtime packages must never recurse from the application loader into themselves");
            final URL resource = bridge.getResource(RESOURCE_PATH);
            assertNotNull(resource, "the system resource API must see the authenticated application resource");
            assertArrayEquals(RESOURCE_BYTES, read(resource.openStream()));

            final Enumeration<URL> resources = bridge.getResources(RESOURCE_PATH);
            assertTrue(resources.hasMoreElements(), "system resource enumeration must delegate to the bound loader");
            assertArrayEquals(RESOURCE_BYTES, read(resources.nextElement().openStream()));
            assertFalse(resources.hasMoreElements(), "the system view must not duplicate its protected root entry");
            assertThrows(IllegalStateException.class, () -> bridge.bind(application),
                    "rebinding would make system resource/class identity depend on launch order");
        } finally {
            try {
                application.close();
            } finally {
                closeCachedArchive(archivePath);
            }
        }
    }
}
