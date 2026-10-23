/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.ArchiveCommand;
import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证有界嵌套容器缓存的生命周期，并确保归档拒绝行为始终失败关闭。
 */
final class NestedArchiveContentTest {
    private static final String CONTAINER_PATH = "BOOT-INF/lib/dependency.jar";
    private static final String TEST_PASSWORD = "nested-cache-test";

    @TempDir
    private Path temporaryDirectory;

    /**
     * 使用由命令持有的密码字节构造领域命令，使所有测试遵循同一清理约定。
     */
    private static void encrypt(Path input, Path output, boolean encryptDependencies) throws IOException {
        final char[] password = TEST_PASSWORD.toCharArray();
        try (ArchiveCommand command = new ArchiveCommand(input, output, password, List.of(), List.of(),
                encryptDependencies, (stage, percent) -> {
        }, CancellationProbe.NEVER)) {
            new JarArchiveOperations().encrypt(command);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 仅在打开受保护归档期间解包测试凭据。
     */
    private static ProtectedArchive openProtected(ArchiveSnapshot snapshot, Path workspace) throws IOException {
        return openProtected(snapshot, workspace, CancellationProbe.NEVER);
    }

    /**
     * 注入取消信号，使私有副本边界的清理行为可测试。
     */
    private static ProtectedArchive openProtected(ArchiveSnapshot snapshot, Path workspace,
                                                  CancellationProbe cancellation) throws IOException {
        final char[] password = TEST_PASSWORD.toCharArray();
        try {
            return ProtectedArchive.open(snapshot, password, workspace, cancellation);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 缓存取得新路径后的首次检查即取消，以验证不完整副本的回滚流程。
     */
    private static CancellationProbe cancelWhenCopyExists(Path workspace) {
        return () -> {
            try (Stream<Path> files = Files.list(workspace)) {
                return files.anyMatch(path -> path.getFileName().toString().startsWith(".xjp-nested-cache-"));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        };
    }

    /**
     * 创建足够多的 class 记录以暴露逐条复制回归，同时保持夹具轻量。
     */
    private static Map<String, byte[]> manyClassEntries(int count) {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            entries.put("dep/Library" + index + ".class", classHeader(8));
        }
        return entries;
    }

    /**
     * 只列出缓存持有的副本，避免生命周期断言把无关工作区文件误判为泄漏。
     */
    private static List<Path> cachedCopies(Path workspace) throws IOException {
        try (Stream<Path> files = Files.list(workspace)) {
            return files.filter(path -> path.getFileName().toString().startsWith(".xjp-nested-cache-"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * 创建外层受保护 JAR，并使嵌套字节与其认证容器摘要不匹配。
     */
    private static void rewriteWithTamperedContainer(Path source, Path output) throws IOException {
        try (JarFile input = new JarFile(source.toFile(), false);
             JarOutputStream target = new JarOutputStream(Files.newOutputStream(output))) {
            final Enumeration<JarEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                target.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream content = input.getInputStream(entry)) {
                    final byte[] bytes = content.readAllBytes();
                    if (CONTAINER_PATH.equals(entry.getName())) {
                        bytes[0] ^= 1;
                    }
                    target.write(bytes);
                }
                target.closeEntry();
            }
        }
    }

    /**
     * 替换等长 ZIP 名称，使有效中央目录中出现两条相同路径。
     */
    private static byte[] duplicateEntryJarBytes() throws IOException {
        final byte[] unique = jarBytes(Map.of("a.txt", new byte[]{1}, "b.txt", new byte[]{2}));
        final byte[] from = "b.txt".getBytes(StandardCharsets.US_ASCII);
        final byte[] to = "a.txt".getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index <= unique.length - from.length; index++) {
            if (matches(unique, from, index)) {
                System.arraycopy(to, 0, unique, index, to.length);
            }
        }
        return unique;
    }

    /**
     * 比较固定宽度 ZIP 名称，不解码也不修改相邻二进制字段。
     */
    private static boolean matches(byte[] source, byte[] value, int offset) {
        for (int index = 0; index < value.length; index++) {
            if (source[offset + index] != value[index]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 写入测试用可执行 manifest 和给定物理条目，不额外依赖框架。
     */
    private static void writeExecutableJar(Path path, Map<String, byte[]> entries) throws IOException {
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "demo.Main");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (final Map.Entry<String, byte[]> fixture : entries.entrySet()) {
                output.putNextEntry(new JarEntry(fixture.getKey()));
                output.write(fixture.getValue());
                output.closeEntry();
            }
        }
    }

    /**
     * 序列化嵌套夹具 JAR，使中央目录可定位且输入顺序确定。
     */
    private static byte[] jarBytes(Map<String, byte[]> entries) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             JarOutputStream output = new JarOutputStream(bytes)) {
            for (final Map.Entry<String, byte[]> fixture : entries.entrySet()) {
                output.putNextEntry(new JarEntry(fixture.getKey()));
                output.write(fixture.getValue());
                output.closeEntry();
            }
            output.finish();
            return bytes.toByteArray();
        }
    }

    /**
     * 生成版本扫描和加密筛选测试所需的最小 class 文件头。
     */
    private static byte[] classHeader(int feature) {
        final int major = feature + 44;
        return new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
                0, 0, (byte) (major >>> 8), (byte) major};
    }

    /**
     * 验证多个逻辑子条目时复用同一份已认证的私有容器副本。
     */
    @Test
    void reusesOnePrivateCopyAcrossManyNestedEntries() throws Exception {
        final Path input = temporaryDirectory.resolve("source.jar");
        final Path encrypted = temporaryDirectory.resolve("protected.jar");
        final Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        final Map<String, byte[]> nestedEntries = manyClassEntries(48);
        final Map<String, byte[]> archiveEntries = new LinkedHashMap<>();
        archiveEntries.put("demo/Main.class", classHeader(8));
        archiveEntries.put(CONTAINER_PATH, jarBytes(nestedEntries));
        writeExecutableJar(input, archiveEntries);
        encrypt(input, encrypted, true);

        try (ArchiveSnapshot snapshot = ArchiveSupport.open(encrypted);
             ProtectedArchive protectedArchive = openProtected(snapshot, workspace)) {
            final List<Path> cachedCopies = cachedCopies(workspace);
            assertEquals(1, cachedCopies.size());
            final List<EntryMetadata> children = protectedArchive.index().entries().stream()
                    .filter(metadata -> metadata.getPath().startsWith(CONTAINER_PATH + "!/"))
                    .toList();
            assertEquals(nestedEntries.size(), children.size());

            for (final EntryMetadata metadata : children) {
                try (InputStream content = protectedArchive.openContent(snapshot, metadata, workspace)) {
                    ArchiveCoverage.requireFacts(metadata, ArchiveSupport.measure(content, null));
                }
                assertEquals(cachedCopies, cachedCopies(workspace));
            }
        }
        assertTrue(cachedCopies(workspace).isEmpty());
    }

    /**
     * 容器摘要不匹配时必须在访问子条目前失败，并删除所有私有缓存副本。
     */
    @Test
    void rejectedContainerAuthenticationCleansItsPrivateCopy() throws Exception {
        final Path input = temporaryDirectory.resolve("source.jar");
        final Path encrypted = temporaryDirectory.resolve("protected.jar");
        final Path tampered = temporaryDirectory.resolve("tampered.jar");
        final Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        final Map<String, byte[]> archiveEntries = new LinkedHashMap<>();
        archiveEntries.put("demo/Main.class", classHeader(8));
        archiveEntries.put(CONTAINER_PATH, jarBytes(Map.of("dep/Library.class", classHeader(8))));
        writeExecutableJar(input, archiveEntries);
        encrypt(input, encrypted, false);
        rewriteWithTamperedContainer(encrypted, tampered);

        try (ArchiveSnapshot snapshot = ArchiveSupport.open(tampered)) {
            assertThrows(IOException.class, () -> openProtected(snapshot, workspace));
        }
        assertTrue(cachedCopies(workspace).isEmpty());
    }

    /**
     * 临时文件创建后立即取消时，不得遗留不完整的私有容器副本。
     */
    @Test
    void cancelledContainerCopyIsRemovedBeforeReturning() throws Exception {
        final Path input = temporaryDirectory.resolve("cancel-source.jar");
        final Path encrypted = temporaryDirectory.resolve("cancel-protected.jar");
        final Path workspace = Files.createDirectory(temporaryDirectory.resolve("workspace"));
        final Map<String, byte[]> archiveEntries = new LinkedHashMap<>();
        archiveEntries.put("demo/Main.class", classHeader(8));
        archiveEntries.put(CONTAINER_PATH, jarBytes(Map.of("dep/Library.class", classHeader(8))));
        writeExecutableJar(input, archiveEntries);
        encrypt(input, encrypted, false);

        try (ArchiveSnapshot snapshot = ArchiveSupport.open(encrypted)) {
            assertThrows(CancellationException.class, () -> openProtected(
                    snapshot, workspace, cancelWhenCopyExists(workspace)));
        }
        assertTrue(cachedCopies(workspace).isEmpty());
    }

    /**
     * 暂存产物发布前仍必须拒绝重复的嵌套路径。
     */
    @Test
    void duplicateNestedEntryIsRejectedWithoutPublishingOutput() throws Exception {
        final Path input = temporaryDirectory.resolve("duplicate-source.jar");
        final Path output = temporaryDirectory.resolve("duplicate-protected.jar");
        final byte[] duplicateNestedJar = duplicateEntryJarBytes();
        final Map<String, byte[]> archiveEntries = new LinkedHashMap<>();
        archiveEntries.put("demo/Main.class", classHeader(8));
        archiveEntries.put(CONTAINER_PATH, duplicateNestedJar);
        writeExecutableJar(input, archiveEntries);

        assertThrows(IOException.class, () -> encrypt(input, output, false));
        assertFalse(Files.exists(output));
    }
}
