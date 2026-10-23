/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 仅验证 Boot classic 的精确兼容标识，普通 JDK 条目仍走原生处理路径。
 */
final class BootClassicEntryPathTest {
    @TempDir
    private Path temporaryDirectory;

    /**
     * 通过反射构造 Boot 提供的归档对象，使生产代码无需链接框架类。
     */
    private static JarFile openBootJar(Class<?> bootJarFileType, File path) throws Exception {
        return (JarFile) bootJarFileType.getConstructor(File.class).newInstance(path);
    }

    /**
     * 关闭 Boot 2.0 的公开归档及其底层句柄，因为该版本的底层 close 实际为空操作。
     */
    private static void closeBootJarFully(JarFile archive) throws Exception {
        Exception closeFailure = null;
        try {
            archive.close();
        } catch (Exception failure) {
            closeFailure = failure;
        }
        try {
            closeBootBackingFile(archive);
        } catch (Exception failure) {
            if (closeFailure == null) {
                closeFailure = failure;
            } else {
                closeFailure.addSuppressed(failure);
            }
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    /**
     * 释放被固定占用的 Boot 2.0 RandomAccessFile，使 Windows 能立即删除临时 fixture。
     */
    private static void closeBootBackingFile(JarFile archive) throws Exception {
        final Field rootFileField = archive.getClass().getDeclaredField("rootFile");
        rootFileField.setAccessible(true);
        final Object rootFile = rootFileField.get(archive);
        final Field randomAccessFileField = rootFile.getClass().getDeclaredField("randomAccessFile");
        randomAccessFileField.setAccessible(true);
        final Object backingFile = randomAccessFileField.get(rootFile);
        if (!(backingFile instanceof RandomAccessFile)) {
            throw new IOException("Provided Boot 2.0 loader has an unexpected backing file implementation");
        }
        ((RandomAccessFile) backingFile).close();
    }

    /**
     * 写入一个真实的小型 ZIP 条目，使 Boot loader 返回其自身的 JarEntry 实现。
     */
    private static void writeJar(Path path) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            output.putNextEntry(new ZipEntry("fixture.txt"));
            output.write(new byte[]{1});
            output.closeEntry();
        }
    }

    /**
     * 避免对 JDK JarFile 返回的普通条目使用框架专属反射。
     */
    @Test
    void returnsNullForJdkJarEntry() throws Exception {
        final Path path = temporaryDirectory.resolve("plain.jar");
        writeJar(path);
        try (JarFile archive = new JarFile(path.toFile(), false)) {
            final JarEntry entry = archive.getJarEntry("fixture.txt");
            assertNull(BootClassicEntryPath.physicalName(entry));
        }
    }

    /**
     * 兼容没有物理名称字段的旧版 Boot loader。
     */
    @Test
    void returnsNullForProvidedLegacyBootEntry() throws Exception {
        final Path path = temporaryDirectory.resolve("legacy-boot.jar");
        writeJar(path);
        final Class<?> bootJarFileType = Class.forName("org.springframework.boot.loader.jar.JarFile");
        final JarFile archive = openBootJar(bootJarFileType, path.toFile());
        try {
            final JarEntry entry = (JarEntry) bootJarFileType.getMethod("getJarEntry", String.class)
                    .invoke(archive, "fixture.txt");
            assertNull(BootClassicEntryPath.physicalName(entry));
        } finally {
            closeBootJarFully(archive);
        }
        Files.delete(path);
    }
}
