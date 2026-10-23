/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 校验认证路径是否完整覆盖外层条目和受支持的嵌套归档条目。
 */
final class ArchiveCoverage {
    /**
 * 覆盖校验由每次认证快照和索引推导，因此禁止创建实例。
     */
    private ArchiveCoverage() {
    }

    /**
 * 校验索引唯一性、保留元数据、外层覆盖范围和唯一受支持的嵌套层级。
     */
    static void validate(ArchiveSnapshot snapshot, ArchiveIndex index,
                         NestedArchiveContent.ContainerCache containers,
                         CancellationProbe cancellation) throws IOException {
        final Set<String> indexed = validateIndex(snapshot, index, cancellation);
        validateDirectEntries(snapshot, indexed, cancellation);
        validateNestedContainers(snapshot, index, indexed, containers, cancellation);
        rejectUnresolvedNestedPaths(indexed, cancellation);
    }

    /**
 * 拒绝重复、保留、缺失或未经认证的原始 manifest 索引记录。
     */
    private static Set<String> validateIndex(ArchiveSnapshot snapshot, ArchiveIndex index,
                                             CancellationProbe cancellation) throws IOException {
        final Set<String> indexed = new HashSet<>();
        boolean originalManifestFound = false;
        for (EntryMetadata metadata : index.entries()) {
            cancellation.check();
            final String path = metadata.getPath();
            if (!indexed.add(path)) {
                throw new IOException("Archive index contains duplicate path: " + path);
            }
            validateIndexPath(snapshot, metadata, path);
            originalManifestFound |= ArchiveSupport.ORIGINAL_MANIFEST.equals(path);
        }
        if (!originalManifestFound) {
            throw new IOException("Authenticated index does not contain the original manifest");
        }
        return indexed;
    }

    /**
 * 控制记录不纳入索引；非嵌套索引记录必须对应真实外层归档条目。
     */
    private static void validateIndexPath(ArchiveSnapshot snapshot, EntryMetadata metadata, String path)
            throws IOException {
        if (ArchiveSupport.MANIFEST.equals(path) || ArchiveSupport.ENVELOPE.equals(path)
            || ArchiveSupport.INDEX.equals(path)) {
            throw new IOException("Archive index contains a reserved path: " + path);
        }
        if (ArchiveSupport.ORIGINAL_MANIFEST.equals(path) && !metadata.isEncrypted()) {
            throw new IOException("Original manifest must be authenticated ciphertext");
        }
        if (!path.contains("!/") && !snapshot.byName().containsKey(path)) {
            throw new IOException("Indexed archive entry is missing: " + path);
        }
    }

    /**
 * 要求受保护外层 JAR 中每个非控制文件都具有一条索引记录。
     */
    private static void validateDirectEntries(ArchiveSnapshot snapshot, Set<String> indexed,
                                              CancellationProbe cancellation) throws IOException {
        for (JarEntry entry : snapshot.entries()) {
            cancellation.check();
            final String path = entry.getName();
            if (entry.isDirectory() || isControlEntry(path)) {
                continue;
            }
            if (!indexed.contains(path)) {
                throw new IOException("Archive entry is absent from the authenticated index: " + path);
            }
        }
    }

    /**
 * 仅将有意不写入索引的信封、索引和 manifest 生成记录识别为控制条目。
     */
    private static boolean isControlEntry(String path) {
        return ArchiveSupport.MANIFEST.equals(path) || ArchiveSupport.ENVELOPE.equals(path)
               || ArchiveSupport.INDEX.equals(path);
    }

    /**
 * 接受逻辑子路径前先重建每个受支持嵌套依赖的可见条目集合。
     */
    private static void validateNestedContainers(ArchiveSnapshot snapshot, ArchiveIndex index,
                                                 Set<String> indexed,
                                                 NestedArchiveContent.ContainerCache containers,
                                                 CancellationProbe cancellation) throws IOException {
        for (JarEntry container : snapshot.entries()) {
            cancellation.check();
            if (container.isDirectory() || !ArchiveSupport.isNestedJar(container.getName(), snapshot.family())) {
                continue;
            }
            validateNestedContainer(index, indexed, containers, cancellation, container);
        }
    }

    /**
 * 遍历嵌套条目目录前，先根据索引摘要校验明文容器。
     */
    private static void validateNestedContainer(ArchiveIndex index, Set<String> indexed,
                                                NestedArchiveContent.ContainerCache containers,
                                                CancellationProbe cancellation, JarEntry container) throws IOException {
        final String containerPath = container.getName();
        if (containerPath.contains("!/")) {
            throw new IOException("Nested archive containers cannot themselves be nested: " + containerPath);
        }
        cancellation.check();
        requirePlainContainer(index, containerPath);
        try (NestedArchiveContent.ContainerLease nested = containers.openArchive(containerPath)) {
            validateNestedEntries(nested.archive(), containerPath, index, indexed, cancellation);
        }
    }

    /**
 * 要求容器本身有明文索引，因为必须先查找嵌套 ZIP 条目，再解密其内容。
     */
    private static EntryMetadata requirePlainContainer(ArchiveIndex index, String path) throws IOException {
        final EntryMetadata metadata = index.get(path);
        if (metadata == null || metadata.isEncrypted()) {
            throw new IOException("Nested archive container must be indexed plaintext: " + path);
        }
        return metadata;
    }

    /**
 * 每个物理子路径必须恰好出现一次，并从未解析的嵌套引用集合中移除。
     */
    private static void validateNestedEntries(JarFile nested, String containerPath, ArchiveIndex index,
                                              Set<String> indexed, CancellationProbe cancellation) throws IOException {
        final Enumeration<JarEntry> entries = nested.entries();
        final Set<String> nestedNames = new HashSet<>();
        while (entries.hasMoreElements()) {
            cancellation.check();
            final JarEntry entry = entries.nextElement();
            ArchiveSupport.validateEntryName(entry.getName(), entry.isDirectory());
            if (!nestedNames.add(entry.getName())) {
                throw new IOException("Nested archive contains a duplicate entry: " + entry.getName());
            }
            removeCoveredChild(index, indexed, containerPath, entry);
        }
    }

    /**
 * 允许嵌套目录记录不对应索引行，但要求每个嵌套文件都能唯一解析。
     */
    private static void removeCoveredChild(ArchiveIndex index, Set<String> indexed, String containerPath,
                                           JarEntry entry) throws IOException {
        if (entry.isDirectory()) {
            return;
        }
        final String logicalPath = containerPath + "!/" + entry.getName();
        if (index.get(logicalPath) == null) {
            throw new IOException("Nested archive entry is absent from authenticated index: " + logicalPath);
        }
        indexed.remove(logicalPath);
    }

    /**
 * 拒绝单层类加载器映射不支持的嵌套索引行。
     */
    private static void rejectUnresolvedNestedPaths(Set<String> indexed,
                                                    CancellationProbe cancellation) throws IOException {
        for (String path : indexed) {
            cancellation.check();
            if (path.contains("!/") && !ArchiveSupport.ORIGINAL_MANIFEST.equals(path)) {
                throw new IOException("Index refers to an unsupported or missing nested path: " + path);
            }
        }
    }

    /**
 * ZIP 解析器信任容器内部条目目录前，先比对容器字节事实。
     */
    static void requireFacts(EntryMetadata metadata, EntryFacts facts) throws IOException {
        if (metadata.getLength() != facts.length() || metadata.getCrc32() != facts.crc32()
            || !MessageDigest.isEqual(metadata.getSha256(), facts.sha256())) {
            throw new IOException("Nested archive container does not match its authenticated index: "
                                  + metadata.getPath());
        }
    }
}
