/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Path;
import java.util.Collections;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 验证可执行 JAR 路径含 URL 编码 Unicode 和空格时，嵌套 Boot URL 标识仍能正确解析。
 *
 * @author kongweiguang
 */
class ArchiveRuntimeNestedUrlIdentityTest {

    /**
     * 构造空认证索引上下文，因为这些检查仅验证归档 URL 标识解析。
     */
    private static ArchiveRuntime runtime(URL rootArchive) throws Exception {
        final java.lang.reflect.Constructor<ArchiveRuntime> constructor = ArchiveRuntime.class.getDeclaredConstructor(
                String.class, byte[].class, ArchiveIndex.class, Manifest.class, URL.class);
        constructor.setAccessible(true);
        return constructor.newInstance("00112233-4455-4677-8899-aabbccddeeff", new byte[32],
                new ArchiveIndex(Collections.<EntryMetadata>emptyList()), new Manifest(), rootArchive);
    }

    /**
     * 直接调用私有匹配器，避免为 URL 标识测试注册 Boot 协议 handler。
     */
    private static String archivePrefix(ArchiveRuntime archive, URL entryArchiveUrl) throws Exception {
        final java.lang.reflect.Method method = ArchiveRuntime.class.getDeclaredMethod("archivePrefix", URL.class);
        method.setAccessible(true);
        return (String) method.invoke(archive, entryArchiveUrl);
    }

    /**
     * 构造 Boot 嵌套协议 URL；本路径匹配测试刻意不执行归档 I/O。
     */
    private static URL nestedUrl(String externalForm) throws Exception {
        return new URL(null, externalForm, new URLStreamHandler() {
            /**
     * 拒绝建立连接，避免归档标识测试打开合成的嵌套归档。
             */
            @Override
            protected URLConnection openConnection(URL url) throws IOException {
                throw new IOException("Synthetic nested URL must not be opened");
            }
        });
    }

    /**
     * 即使 Boot 通过 nested: 协议 handler 提供 URL，也能匹配到同一外层可执行归档。
     */
    @Test
    void resolvesBootClassesRootForEncodedArchivePath(@TempDir final Path tempDir) throws Exception {
        final Path archivePath = tempDir.resolve("中文 应用").resolve("protected.jar");
        final URL rootArchive = archivePath.toUri().toURL();
        final String rootPath = rootArchive.toExternalForm().substring("file:".length());
        final URL bootClassesUrl = nestedUrl("nested:" + rootPath + "/!BOOT-INF/classes/");
        final ArchiveRuntime archive = runtime(rootArchive);

        assertEquals("BOOT-INF/classes", archivePrefix(archive, bootClassesUrl));
    }

    /**
     * 根路径转义 Unicode、而 JAR handler 保留原字符时，仍能定位到同一 Windows 文件。
     */
    @Test
    void resolvesMixedEscapingForRootArchive() throws Exception {
        final URL encodedRoot = new URL(
                "file:/C:/workspace/XJarPlus/%e4%bb%a3%e7%a0%81/encrypted%20ordinary%20app.jar");
        final URL mixedEntry = new URL(
                "file:/C:/workspace/XJarPlus/代码/encrypted%20ordinary%20app.jar");

        assertEquals("", archivePrefix(runtime(encodedRoot), mixedEntry));
    }

    /**
     * 匹配 URL 路径段时，将名称相似的外部 JAR 排除在受保护归档标识之外。
     */
    @Test
    void doesNotMatchArchivePathPrefixWithoutNestedSeparator(@TempDir final Path tempDir) throws Exception {
        final Path archivePath = tempDir.resolve("中文 应用").resolve("protected.jar");
        final URL rootArchive = archivePath.toUri().toURL();
        final String rootPath = rootArchive.toExternalForm().substring("file:".length());
        final URL siblingArchiveUrl = nestedUrl("nested:" + rootPath + ".other.jar/!BOOT-INF/classes/");
        final ArchiveRuntime archive = runtime(rootArchive);

        assertNull(archivePrefix(archive, siblingArchiveUrl));
    }
}
