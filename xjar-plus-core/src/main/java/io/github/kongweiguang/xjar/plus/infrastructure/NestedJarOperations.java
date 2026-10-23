/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import io.github.kongweiguang.xjar.plus.format.ChunkCipher;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * 仅重写显式选中的依赖，并为嵌套可执行依赖中的每个逻辑条目建立索引。
 */
final class NestedJarOperations {
    /**
     * 每次嵌套重写都由明确的归档和操作参数限定，因此禁止创建实例。
     */
    private NestedJarOperations() {
    }

    /**
     * 复制并认证嵌套依赖，同时将外层 ZIP 记录保持为 STORED，以兼容原生启动器。
     */
    static void writeContainer(ArchiveSnapshot outer, JarEntry sourceEntry, String path, JarOutputStream output,
                               Path workspace, ArchiveTransform transform) throws IOException {
        final Path original = Files.createTempFile(workspace, ".xjp-library-", ".jar");
        Path replacement = null;
        try {
            final CancellationProbe cancellation = transform.command().cancellation();
            final EntryFacts originalFacts = copyEntry(outer.jar(), sourceEntry, original, cancellation);
            final ArchiveSnapshot nested = ArchiveSupport.open(original);
            try (nested) {
                if (nested.encrypted()) {
                    throw new IOException("Nested libraries already protected by XJar Plus cannot be repackaged: "
                                          + path);
                }
                final List<JarEntry> files = files(nested);
                final boolean modify = transform.encryptDependencies() && files.stream().anyMatch(entry ->
                        shouldEncrypt(path, entry.getName(), transform.selection()));
                if (modify) {
                    ArchiveSupport.rejectSigned(nested);
                    replacement = Files.createTempFile(workspace, ".xjp-library-protected-", ".jar");
                    rewriteNested(nested, path, replacement, transform);
                } else {
                    addPlainIndex(nested, path, transform.index(), cancellation);
                }
            }
            final Path finalJar = replacement == null ? original : replacement;
            final EntryFacts finalFacts = replacement == null ? originalFacts : measureFile(finalJar, cancellation);
            writeStored(output, sourceEntry, finalJar, finalFacts, cancellation);
            transform.index().add(ArchiveSupport.metadata(path, false, finalFacts));
        } finally {
            Files.deleteIfExists(original);
            if (replacement != null) {
                Files.deleteIfExists(replacement);
            }
        }
    }

    /**
     * 解密时将嵌套 JAR 恢复为明文，并逐项恢复所选 class 和资源。
     */
    static void writeDecryptedContainer(ArchiveSnapshot outer, JarEntry sourceEntry, String path,
                                        JarOutputStream output, Path workspace, ProtectedArchive protectedArchive,
                                        List<EntryMetadata> index, CancellationProbe cancellation) throws IOException {
        final Path original = Files.createTempFile(workspace, ".xjp-library-read-", ".jar");
        Path restored = null;
        try {
            final EntryFacts containerFacts = copyEntry(outer.jar(), sourceEntry, original, cancellation);
            final EntryMetadata expectedContainer = protectedArchive.index().get(path);
            requireFacts(expectedContainer, containerFacts, path);
            final boolean encryptedChild = protectedArchive.index().entries().stream()
                    .anyMatch(metadata -> metadata.isEncrypted() && metadata.getPath().startsWith(path + "!/"));
            if (!encryptedChild) {
                writeStored(output, sourceEntry, original, containerFacts, cancellation);
                index.add(ArchiveSupport.metadata(path, false, containerFacts));
                indexNestedPlain(original, path, protectedArchive, index, cancellation);
                return;
            }
            restored = Files.createTempFile(workspace, ".xjp-library-restored-", ".jar");
            final ArchiveSnapshot nested = ArchiveSupport.open(original);
            try (nested; JarOutputStream nestedOutput = new JarOutputStream(Files.newOutputStream(restored))) {
                for (JarEntry entry : nested.entries()) {
                    cancellation.check();
                    if (entry.isDirectory()) {
                        final JarEntry directory = ArchiveSupport.outputEntry(entry);
                        nestedOutput.putNextEntry(directory);
                        nestedOutput.closeEntry();
                        continue;
                    }
                    final String logicalPath = path + "!/" + entry.getName();
                    final EntryMetadata metadata = protectedArchive.index().get(logicalPath);
                    if (metadata == null) {
                        throw new IOException("Nested entry is absent from authenticated index: " + logicalPath);
                    }
                    final JarEntry restoredEntry = ArchiveSupport.outputEntry(entry);
                    nestedOutput.putNextEntry(restoredEntry);
                    try (InputStream content = openNestedEntry(nested, entry, metadata,
                            protectedArchive, cancellation)) {
                        final EntryFacts facts = ArchiveSupport.measure(content, nestedOutput);
                        requireFacts(metadata, facts, logicalPath);
                        index.add(ArchiveSupport.metadata(logicalPath, false, facts));
                    }
                    nestedOutput.closeEntry();
                }
            }
            final EntryFacts restoredFacts = measureFile(restored, cancellation);
            writeStored(output, sourceEntry, restored, restoredFacts, cancellation);
            index.add(ArchiveSupport.metadata(path, false, restoredFacts));
        } finally {
            Files.deleteIfExists(original);
            if (restored != null) {
                Files.deleteIfExists(restored);
            }
        }
    }

    /**
     * 扫描所有直接的非目录依赖条目，使运行时 URL 查找具有已认证的逻辑路径。
     */
    static void addPlainIndex(ArchiveSnapshot nested, String containerPath, List<EntryMetadata> index,
                              CancellationProbe cancellation) throws IOException {
        for (JarEntry entry : files(nested)) {
            cancellation.check();
            final String path = containerPath + "!/" + entry.getName();
            try (InputStream input = nested.jar().getInputStream(entry)) {
                index.add(ArchiveSupport.metadata(path, false, ArchiveSupport.measure(input, null)));
            }
        }
    }

    /**
     * 使用共用数据密钥和路径绑定 AAD 重写选中条目，其余字节保持原样。
     */
    private static void rewriteNested(ArchiveSnapshot nested, String containerPath, Path outputPath,
                                      ArchiveTransform transform) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(outputPath))) {
            for (JarEntry entry : nested.entries()) {
                transform.command().cancellation().check();
                if (entry.isDirectory()) {
                    output.putNextEntry(ArchiveSupport.outputEntry(entry));
                    output.closeEntry();
                    continue;
                }
                final String path = containerPath + "!/" + entry.getName();
                final boolean encrypt = shouldEncrypt(containerPath, entry.getName(), transform.selection());
                final EntryFacts facts;
                if (encrypt) {
                    try (InputStream input = nested.jar().getInputStream(entry)) {
                        facts = ArchiveSupport.measure(guard(input, transform.command().cancellation()), null);
                    }
                    final JarEntry target = ArchiveSupport.outputEntry(entry);
                    output.putNextEntry(target);
                    try (InputStream input = nested.jar().getInputStream(entry)) {
                        ChunkCipher.encrypt(guard(input, transform.command().cancellation()), output,
                                transform.key(), transform.artifactId(), path, facts.length(), transform.nonces());
                    } catch (java.security.GeneralSecurityException failure) {
                        throw new IOException("Unable to encrypt nested dependency entry: " + path, failure);
                    }
                    output.closeEntry();
                } else {
                    output.putNextEntry(ArchiveSupport.outputEntry(entry));
                    try (InputStream input = nested.jar().getInputStream(entry)) {
                        facts = ArchiveSupport.measure(guard(input, transform.command().cancellation()), output);
                    }
                    output.closeEntry();
                }
                transform.index().add(ArchiveSupport.metadata(path, encrypt, facts));
            }
        }
    }

    /**
     * 嵌套 JAR 没有加密子条目时仍恢复其逻辑条目索引元数据。
     */
    private static void indexNestedPlain(Path archive, String containerPath, ProtectedArchive protectedArchive,
                                         List<EntryMetadata> index, CancellationProbe cancellation)
            throws IOException {
        final ArchiveSnapshot nested = ArchiveSupport.open(archive);
        try (nested) {
            for (JarEntry entry : files(nested)) {
                cancellation.check();
                final String path = containerPath + "!/" + entry.getName();
                final EntryMetadata metadata = protectedArchive.index().get(path);
                if (metadata == null) {
                    throw new IOException("Nested entry is absent from authenticated index: " + path);
                }
                try (InputStream input = nested.jar().getInputStream(entry)) {
                    final EntryFacts facts = ArchiveSupport.measure(input, null);
                    requireFacts(metadata, facts, path);
                    index.add(ArchiveSupport.metadata(path, false, facts));
                }
            }
        }
    }

    /**
     * 打开源条目，并且仅在认证元数据表明需要时执行分块解密。
     */
    private static InputStream openNestedEntry(ArchiveSnapshot nested, JarEntry entry, EntryMetadata metadata,
                                               ProtectedArchive protectedArchive,
                                               CancellationProbe cancellation) throws IOException {
        final InputStream raw = guard(nested.jar().getInputStream(entry), cancellation);
        if (!metadata.isEncrypted()) {
            return raw;
        }
        try {
            return guard(io.github.kongweiguang.xjar.plus.format.ChunkCipher.decryptingStream(raw,
                    protectedArchive.dataKey(), protectedArchive.artifactId(), metadata.getPath(),
                    metadata.getLength()), cancellation);
        } catch (RuntimeException | IOException failure) {
            raw.close();
            throw failure;
        }
    }

    /**
     * 将单个原始嵌套容器复制到私有暂存区，并计算其不可变 ZIP 属性。
     */
    private static EntryFacts copyEntry(JarFile source, JarEntry entry, Path output,
                                        CancellationProbe cancellation) throws IOException {
        try (InputStream input = guard(source.getInputStream(entry), cancellation);
             OutputStream destination = Files.newOutputStream(output)) {
            return ArchiveSupport.measure(input, destination);
        }
    }

    /**
     * 嵌套归档成为 STORED 外层 ZIP 记录前，完整测量其文件属性。
     */
    private static EntryFacts measureFile(Path path, CancellationProbe cancellation) throws IOException {
        try (InputStream input = guard(Files.newInputStream(path), cancellation)) {
            return ArchiveSupport.measure(input, null);
        }
    }

    /**
     * 写入已知大小和 CRC 的嵌套 JAR，使 Boot/Solon 启动器可直接定位而无需解压。
     */
    private static void writeStored(JarOutputStream output, JarEntry source, Path path, EntryFacts facts,
                                    CancellationProbe cancellation) throws IOException {
        final JarEntry target = ArchiveSupport.outputEntry(source);
        target.setMethod(JarEntry.STORED);
        target.setSize(facts.length());
        target.setCompressedSize(facts.length());
        target.setCrc(facts.crc32());
        output.putNextEntry(target);
        try (InputStream input = guard(Files.newInputStream(path), cancellation)) {
            ArchiveSupport.measure(input, output);
        }
        output.closeEntry();
    }

    /**
     * 只读取一次归档条目列表，确保目录不会进入认证文件索引。
     */
    private static List<JarEntry> files(ArchiveSnapshot snapshot) {
        final List<JarEntry> result = new ArrayList<>();
        for (JarEntry entry : snapshot.entries()) {
            if (!entry.isDirectory()) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 按逻辑依赖路径应用筛选规则，并保留加载器 manifest 和签名记录。
     */
    private static boolean shouldEncrypt(String container, String path, ArchiveSelection selection) {
        final String upper = path.toUpperCase(java.util.Locale.ROOT);
        return !"META-INF/MANIFEST.MF".equals(upper) && !ArchiveSupport.isSignatureMetadata(path)
               && selection.selected(container + "!/" + path);
    }

    /**
     * 将摘要元数据校验与 GCM 分开，使明文和密文共用同一验证契约。
     */
    private static void requireFacts(EntryMetadata metadata, EntryFacts facts, String path) throws IOException {
        if (metadata == null || metadata.getLength() != facts.length() || metadata.getCrc32() != facts.crc32()
            || !java.security.MessageDigest.isEqual(metadata.getSha256(), facts.sha256())) {
            throw new IOException("Archive entry does not match its authenticated index: " + path);
        }
    }

    /**
     * 在有界流读取之间检查取消信号，不改变字节分帧和调用方的资源所有权。
     */
    private static InputStream guard(InputStream input, CancellationProbe cancellation) {
        return new java.io.FilterInputStream(input) {
            /** Checks user cancellation before a bulk read can block the archive operation for another chunk. */
            @Override
            public int read() throws IOException {
                cancellation.check();
                return super.read();
            }

            /** Checks cancellation before each bounded transfer while preserving the wrapped stream's result. */
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                cancellation.check();
                return super.read(bytes, offset, length);
            }
        };
    }
}
