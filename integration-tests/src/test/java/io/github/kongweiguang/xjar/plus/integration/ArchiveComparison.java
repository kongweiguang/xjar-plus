package io.github.kongweiguang.xjar.plus.integration;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 按条目名称与摘要比较归档内容，避免重新打包的元数据变化掩盖内容丢失。
 *
 * @author kongweiguang
 */
final class ArchiveComparison {
    /**
     * 归档比较仅依赖有界的静态摘要操作，无需创建辅助类实例。
     */
    private ArchiveComparison() {
    }

    /**
     * 以流方式计算各条目的 SHA-256，避免大型框架依赖使还原校验占用无界内存。
     */
    static Map<String, String> entryDigests(JarFile archive) throws IOException {
        Map<String, String> digests = new TreeMap<>();
        Enumeration<JarEntry> entries = archive.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            try (InputStream input = archive.getInputStream(entry)) {
                digests.put(entry.getName(), digest(input));
            }
        }
        return digests;
    }

    /**
     * 比较内容摘要而非 ZIP 时间戳或压缩字节，因为这些元数据不属于还原后的条目内容。
     */
    private static String digest(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }
}
