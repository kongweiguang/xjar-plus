/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.jar.JarEntry;

/**
 * 解析 Boot classic 选中的物理条目，不猜测 multi-release 版本。
 */
public final class BootClassicEntryPath {
    private static final String BOOT_JAR_ENTRY = "org.springframework.boot.loader.jar.JarEntry";
    private static final String ASCII_BYTES = "org.springframework.boot.loader.jar.AsciiBytes";
    private static final String HEADER_NAME = "headerName";

    /**
     * 避免实例化；解析过程是针对给定条目的无状态兼容处理。
     */
    private BootClassicEntryPath() {
    }

    /**
     * 读取 Boot classic 中央目录中的条目名；旧版没有该元数据时由调用方决定后备路径。
     */
    public static String physicalName(JarEntry entry) throws IOException {
        if (entry == null || !BOOT_JAR_ENTRY.equals(entry.getClass().getName())) {
            return null;
        }
        final Field field;
        try {
            field = entry.getClass().getDeclaredField(HEADER_NAME);
        } catch (NoSuchFieldException legacyBootEntry) {
            return null;
        }
        return readHeaderName(entry, field);
    }

    /**
     * 将不可访问或结构已变化的元数据视为损坏，避免随意选择其他条目。
     */
    private static String readHeaderName(JarEntry entry, Field field) throws IOException {
        if (!ASCII_BYTES.equals(field.getType().getName())) {
            throw new IOException("Spring Boot classic physical entry metadata has an unsupported type");
        }
        try {
            field.setAccessible(true);
            final Object headerName = field.get(entry);
            if (headerName == null) {
                throw new IOException("Spring Boot classic physical entry metadata is missing");
            }
            final String path = headerName.toString();
            if (path == null || path.isEmpty() || path.indexOf('\0') >= 0) {
                throw new IOException("Spring Boot classic physical entry metadata is invalid");
            }
            return path;
        } catch (IllegalAccessException | RuntimeException failure) {
            throw new IOException("Unable to read Spring Boot classic physical entry metadata", failure);
        }
    }
}
