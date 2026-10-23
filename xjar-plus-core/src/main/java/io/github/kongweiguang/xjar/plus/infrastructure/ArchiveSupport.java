/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.ArchiveSummary;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

/**
 * 共用结构校验和流处理逻辑，使所有归档入口遵循相同安全规则。
 */
final class ArchiveSupport {
    static final String MANIFEST = "META-INF/MANIFEST.MF";
    static final String META_ROOT = "META-INF/xjar-plus/";
    static final String ENVELOPE = META_ROOT + "envelope.bin";
    static final String INDEX = META_ROOT + "index.bin";
    static final String ORIGINAL_MANIFEST = META_ROOT + "original-manifest.mf";
    static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    /**
 * 结构和流处理规则在各操作间无状态，因此禁止创建实例。
     */
    private ArchiveSupport() {
    }

    /**
 * 在任何归档字节进入输出事务前，先打开并校验原始 ZIP 条目。
     */
    static ArchiveSnapshot open(Path input) throws IOException {
        if (input == null || !java.nio.file.Files.isRegularFile(input)) {
            throw new IOException("Input JAR does not exist or is not a regular file: " + input);
        }
        final JarFile jar = new JarFile(input.toFile(), false);
        try {
            final List<JarEntry> entries = new ArrayList<>();
            final Map<String, JarEntry> byName = new HashMap<>();
            readEntries(jar, entries, byName);
            final byte[] manifestBytes = readManifestBytes(jar, byName);
            final Manifest manifest = parseManifest(manifestBytes);
            final boolean encrypted = ArchiveLayout.encryptedMetadata(byName, manifest);
            final ArchiveFamily family = validateAndResolveFamily(byName, manifest, encrypted);
            final Integer minimumJava = minimumJava(jar, entries, family, encrypted);
            final String appMain = applicationMainClass(manifest, family, encrypted);
            final ArchiveSnapshot.Facts facts = new ArchiveSnapshot.Facts(manifest, manifestBytes, family,
                    appMain, encrypted, minimumJava);
            return new ArchiveSnapshot(jar, entries, byName, facts);
        } catch (IOException | RuntimeException e) {
            jar.close();
            throw e;
        }
    }

    /**
 * 单独处理归档清单校验，使 open() 的资源所有权和清理路径清晰。
     */
    private static void readEntries(JarFile jar, List<JarEntry> entries, Map<String, JarEntry> byName)
            throws IOException {
        final Enumeration<JarEntry> enumeration = jar.entries();
        while (enumeration.hasMoreElements()) {
            final JarEntry entry = enumeration.nextElement();
            ArchivePathPolicy.validate(entry.getName(), entry.isDirectory());
            if (byName.putIfAbsent(entry.getName(), entry) != null) {
                throw new IOException("Archive contains duplicate entry: " + entry.getName());
            }
            entries.add(entry);
        }
    }

    /**
 * 只读取有界的 manifest 字节，供家族识别和精确恢复使用。
     */
    private static byte[] readManifestBytes(JarFile jar, Map<String, JarEntry> byName) throws IOException {
        final JarEntry manifestEntry = byName.get(MANIFEST);
        if (manifestEntry == null) {
            return null;
        }
        return readBounded(jar.getInputStream(manifestEntry), MAX_MANIFEST_BYTES);
    }

    /**
 * 解析已限制长度的 manifest；没有 manifest 的归档仍以 null 表示。
     */
    private static Manifest parseManifest(byte[] manifestBytes) throws IOException {
        if (manifestBytes == null) {
            return null;
        }
        return new Manifest(new ByteArrayInputStream(manifestBytes));
    }

    /**
 * 选择归档家族适配器前，先应用失败关闭的元数据规则。
     */
    private static ArchiveFamily validateAndResolveFamily(Map<String, JarEntry> byName, Manifest manifest,
                                                          boolean encrypted) throws IOException {
        if (encrypted) {
            ArchiveLayout.validateEncryptedEntries(byName, manifest);
            return ArchiveLayout.encryptedFamily(manifest);
        }
        ArchivePathPolicy.rejectReservedNames(byName.keySet());
        return ArchiveLayout.plainFamily(manifest, byName);
    }

    /**
 * 加密字节码的 Java 版本要求在认证验证并扫描 class 文件头前保持未知。
     */
    private static Integer minimumJava(JarFile jar, List<JarEntry> entries, ArchiveFamily family, boolean encrypted)
            throws IOException {
        if (encrypted) {
            return null;
        }
        return Math.max(family.minimumJava(), ArchiveVersionScanner.maximumBaseClassVersion(jar, entries, family));
    }

    /**
 * 避免将受保护启动器的实现类误报为应用入口类。
     */
    private static String applicationMainClass(Manifest manifest, ArchiveFamily family, boolean encrypted) {
        if (encrypted) {
            return null;
        }
        return ArchiveLayout.applicationMainClass(manifest, family);
    }

    /**
 * 只检查已校验的归档结构；加密载荷认证由 verify() 负责。
     */
    static ArchiveSummary inspect(Path input) throws IOException {
        try (ArchiveSnapshot snapshot = open(input)) {
            return summary(snapshot);
        }
    }

    /**
 * 将快照转换为稳定的领域事实，不暴露 ZIP 实现对象。
     */
    static ArchiveSummary summary(ArchiveSnapshot snapshot) {
        return new ArchiveSummary(snapshot.family().displayName(), snapshot.applicationMainClass(),
                snapshot.encrypted(), snapshot.entries().size(), snapshot.minimumJava());
    }

    /**
 * 根据有界流式测量结果为加密条目和透传文件创建元数据。
     */
    static EntryMetadata metadata(String path, boolean encrypted, EntryFacts facts) {
        return new EntryMetadata(path, encrypted, facts.length(), facts.crc32(), facts.sha256());
    }

    /**
 * 以恒定内存测量完整条目，避免大型资源需要整文件缓冲。
     */
    static EntryFacts measure(InputStream input, java.io.OutputStream copyTo) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final CRC32 crc = new CRC32();
            final byte[] buffer = new byte[64 * 1024];
            long length = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                digest.update(buffer, 0, read);
                crc.update(buffer, 0, read);
                if (copyTo != null) {
                    copyTo.write(buffer, 0, read);
                }
                length = Math.addExact(length, read);
            }
            return new EntryFacts(length, crc.getValue(), digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available in this Java runtime", e);
        } catch (ArithmeticException e) {
            throw new IOException("Archive entry exceeds the supported length", e);
        }
    }

    /**
 * 在解析器根据内容分配内存前，对格式元数据条目实施硬性读取上限。
     */
    static byte[] readBounded(InputStream input, int limit) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = source.read(buffer)) != -1) {
                total = Math.addExact(total, read);
                if (total > limit) {
                    throw new IOException("Archive metadata exceeds its size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (ArithmeticException e) {
            throw new IOException("Archive metadata size overflow", e);
        }
    }

    /**
 * 写入一条生成的归档条目，时间戳保持确定且不继承攻击者提供的额外字段。
     */
    static void writeBytes(JarOutputStream output, String name, byte[] bytes) throws IOException {
        final JarEntry entry = new JarEntry(name);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    /**
 * 仅在目录条目不存在时写入，避免源目录记录与生成路径冲突。
     */
    static void writeDirectory(JarOutputStream output, String name, long timestamp) throws IOException {
        final JarEntry entry = new JarEntry(name);
        entry.setTime(timestamp < 0 ? 0L : timestamp);
        output.putNextEntry(entry);
        output.closeEntry();
    }

    /**
 * 保留原条目时间戳；普通资源的压缩方式交由 ZIP 写入器选择。
     */
    static JarEntry outputEntry(JarEntry source) {
        final JarEntry target = new JarEntry(source.getName());
        target.setTime(source.getTime() < 0 ? 0L : source.getTime());
        if (source.getComment() != null) {
            target.setComment(source.getComment());
        }
        return target;
    }

    /**
 * 仅修改启动器入口并创建新 manifest；原始 manifest 字节另行认证保存。
     */
    static byte[] launcherManifest(Manifest source, ArchiveFamily family) throws IOException {
        if (source == null) {
            throw new IOException("Executable JAR must contain META-INF/MANIFEST.MF");
        }
        final Manifest copy = new Manifest(source);
        final Attributes attributes = copy.getMainAttributes();
        attributes.putValue("Main-Class", family.runtimeMainClass());
        attributes.putValue("XJar-Plus-Version", "2");
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy.write(output);
        return output.toByteArray();
    }

    /**
 * 直接恢复原始 manifest 字节，不重新序列化以免改变无关属性。
     */
    static byte[] originalManifest(ArchiveSnapshot snapshot) throws IOException {
        if (snapshot.manifestBytes() == null) {
            throw new IOException("Executable JAR must contain META-INF/MANIFEST.MF");
        }
        return snapshot.manifestBytes();
    }

    /**
 * 加密操作替换 manifest 启动器前，先验证选定的可执行归档家族。
     */
    static void requireExecutable(ArchiveSnapshot snapshot) throws IOException {
        if (snapshot.manifest() == null || snapshot.applicationMainClass() == null
            || snapshot.applicationMainClass().isBlank()) {
            throw new IOException("Input JAR must declare a supported executable Main-Class");
        }
    }

    /**
 * 修改前拒绝带签名的归档，避免保留已失效的签名记录并误导使用方。
     */
    static void rejectSigned(ArchiveSnapshot snapshot) throws IOException {
        for (String name : snapshot.byName().keySet()) {
            if (ArchivePathPolicy.isSignedMetadata(name)) {
                throw new IOException("Signed JARs cannot be modified safely: " + name);
            }
        }
    }

    /**
 * 与嵌套归档读取器共用外层条目路径规则，使两层对别名的处理一致。
     */
    static void validateEntryName(String name, boolean directory) throws IOException {
        ArchivePathPolicy.validate(name, directory);
    }

    /**
 * 根据签名记录所在位置识别，不仅依赖文件名。
     */
    static boolean isSignatureMetadata(String name) {
        return ArchivePathPolicy.isSignatureMetadata(name);
    }

    /**
 * 返回该路径是否为按逻辑子 JAR 处理的常规嵌套依赖归档。
     */
    static boolean isNestedJar(String name, ArchiveFamily family) {
        return ArchivePathPolicy.isNestedJar(name, family);
    }

    /**
 * 根据当前快照生成家族摘要，用于暂存产物写入后的验证。
     */
    static ArchiveSummary summary(Path path) throws IOException {
        return inspect(path);
    }
}
