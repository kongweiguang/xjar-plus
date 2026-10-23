/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 根据外层和嵌套归档中的基础 class 文件头计算最低 Java feature 版本。
 */
final class ArchiveVersionScanner {
    /**
 * 每次扫描只使用调用方提供的归档句柄和条目，因此禁止创建实例。
     */
    private ArchiveVersionScanner() {
    }

    /**
 * 仅读取 class 文件头；计算基础版本时排除多版本覆盖项和模块描述符。
     */
    static int maximumBaseClassVersion(JarFile jar, List<JarEntry> entries, ArchiveFamily family) throws IOException {
        int minimum = 8;
        for (JarEntry entry : entries) {
            final String name = entry.getName();
            if (entry.isDirectory()) {
                continue;
            }
            if (isBaseClass(name)) {
                try (InputStream input = jar.getInputStream(entry)) {
                    minimum = Math.max(minimum, classFeatureVersion(input));
                }
            }
            if (ArchivePathPolicy.isNestedJar(name, family)) {
                try (InputStream input = jar.getInputStream(entry)) {
                    minimum = Math.max(minimum, nestedBaseClassVersion(input, name));
                }
            }
        }
        return minimum;
    }

    /**
 * 扫描子归档 class 文件头而不缓存文件体，使启动器预检保持有界内存。
     */
    private static int nestedBaseClassVersion(InputStream input, String container) throws IOException {
        int minimum = 8;
        final Set<String> names = new HashSet<>();
        try (ZipInputStream nested = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = nested.getNextEntry()) != null) {
                ArchivePathPolicy.validate(entry.getName(), entry.isDirectory());
                if (!names.add(entry.getName())) {
                    throw new IOException("Nested archive contains a duplicate entry: " + entry.getName());
                }
                if (!entry.isDirectory() && isBaseClass(entry.getName())) {
                    minimum = Math.max(minimum, classFeatureVersion(nested));
                }
                nested.closeEntry();
            }
        } catch (IOException failure) {
            throw new IOException("Cannot inspect nested archive class versions: " + container, failure);
        }
        return minimum;
    }

    /**
 * 读取固定长度 class 文件头，并将主版本号映射为对应的最低 Java feature 版本。
     */
    private static int classFeatureVersion(InputStream input) throws IOException {
        return classFeatureVersion(input.readNBytes(8));
    }

    /**
 * 读取并回放已验证的内容前缀，避免推导 class 版本时消耗认证流中的字节。
     */
    static int classFeatureVersion(String indexedPath, PushbackInputStream input) throws IOException {
        if (!isBaseClass(indexedPath)) {
            return 8;
        }
        final byte[] header = input.readNBytes(8);
        input.unread(header);
        return classFeatureVersion(header);
    }

    /**
 * 仅根据有界 class 文件头推导最低 Java feature 版本，不缓存 class 文件体。
     */
    private static int classFeatureVersion(byte[] header) {
        if (header.length != 8 || ByteBuffer.wrap(header).getInt() != 0xcafebabe) {
            return 8;
        }
        final int major = ((header[6] & 0xff) << 8) | (header[7] & 0xff);
        return major >= 45 ? Math.max(8, major - 44) : 8;
    }

    /**
 * 忽略多版本覆盖项和模块描述符，因为它们都不会提高基础运行时版本要求。
     */
    private static boolean isBaseClass(String path) {
        final String entryPath = logicalEntryPath(path);
        return entryPath.endsWith(".class") && !entryPath.startsWith("META-INF/versions/")
               && !"module-info.class".equals(entryPath) && !entryPath.endsWith("/module-info.class");
    }

    /**
 * 应用 JAR 类路径规则前只移除已认证的嵌套归档和 Boot classes 前缀。
     */
    private static String logicalEntryPath(String path) {
        final int nestedSeparator = path.indexOf("!/");
        final String entryPath = nestedSeparator < 0 ? path : path.substring(nestedSeparator + 2);
        final String bootClassesPrefix = "BOOT-INF/classes/";
        return entryPath.startsWith(bootClassesPrefix)
                ? entryPath.substring(bootClassesPrefix.length()) : entryPath;
    }
}
