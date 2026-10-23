/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.ChunkCipher;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 持有校验后的嵌套 JAR 私有副本，并限制同时打开的 JarFile 句柄数量。
 */
final class NestedArchiveContent {
    /**
     * 归档访问由嵌套缓存实例管理，因此禁止创建工具类实例。
     */
    private NestedArchiveContent() {
    }

    /**
     * 关闭部分打开的资源，同时保留嵌套条目处理的原始异常。
     */
    private static void closeFailedResources(InputStream raw, ContainerLease lease, Throwable failure) {
        if (raw != null) {
            try {
                raw.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        lease.close();
    }

    /**
     * 立即尝试回滚，并保留缓存持有的路径以供关闭时重试清理。
     */
    private static void deleteFailedCopy(Path copy, Throwable failure) {
        try {
            Files.deleteIfExists(copy);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    /**
     * 保留首次清理异常，同时记录后续句柄和路径清理失败。
     */
    private static IOException mergeFailure(IOException first, IOException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    /**
     * 每个容器只复用一份经认证的磁盘副本，同时限制打开文件句柄数量。
     */
    static final class ContainerCache implements AutoCloseable {
        private static final int MAX_OPEN_JARS = 4;

        private final ArchiveSnapshot snapshot;
        private final ArchiveIndex index;
        private final Path workspace;
        private final CancellationProbe cancellation;
        private final Map<String, CachedContainer> containers = new LinkedHashMap<>(16, 0.75f, true);
        private final Set<Path> ownedCopies = new LinkedHashSet<>();
        private int openJars;
        private boolean closed;

        /**
     * 将缓存副本绑定到指定认证归档快照和操作持有的工作区。
         */
        ContainerCache(ArchiveSnapshot snapshot, ArchiveIndex index, Path workspace,
                       CancellationProbe cancellation) {
            this.snapshot = snapshot;
            this.index = index;
            this.workspace = workspace;
            this.cancellation = cancellation;
        }

        /**
     * 为覆盖检查打开已校验嵌套归档的租约，但不转移 JarFile 所有权。
         */
        synchronized ContainerLease openArchive(String containerPath) throws IOException {
            ensureOpen();
            cancellation.check();
            CachedContainer container = containers.get(containerPath);
            if (container == null) {
                container = copyAndValidate(containerPath);
                containers.put(containerPath, container);
            }
            if (container.jar == null) {
                makeRoomForJar(container);
                container.jar = new JarFile(container.copy.toFile(), false);
                openJars++;
            }
            container.openLeases++;
            return new ContainerLease(this, container, container.jar);
        }

        /**
     * 打开一个已索引子条目，并在返回流关闭前固定其所属容器。
         */
        @SuppressWarnings("PMD.CloseResource")
        InputStream openEntry(EntryMetadata metadata, byte[] dataKey, String artifactId) throws IOException {
            final String path = metadata.getPath();
            final int separator = path.indexOf("!/");
            if (separator < 0 || path.indexOf("!/", separator + 2) >= 0) {
                throw new IOException("Nested archive path has an unsupported depth: " + path);
            }
            final String containerPath = path.substring(0, separator);
            final String nestedPath = path.substring(separator + 2);
            final ContainerLease lease = openArchive(containerPath);
            InputStream raw = null;
            try {
                final JarEntry entry = lease.archive().getJarEntry(nestedPath);
                if (entry == null || entry.isDirectory()) {
                    throw new IOException("Authenticated nested entry is missing: " + path);
                }
                raw = lease.archive().getInputStream(entry);
                final InputStream content = metadata.isEncrypted()
                        ? ChunkCipher.decryptingStream(raw, dataKey, artifactId, path, metadata.getLength()) : raw;
                return new NestedEntryStream(content, lease);
            } catch (IOException | RuntimeException failure) {
                closeFailedResources(raw, lease, failure);
                throw failure;
            }
        }

        /**
     * 信任容器中的嵌套 ZIP 目录前，仅复制并认证该容器一次。
         */
        private CachedContainer copyAndValidate(String containerPath) throws IOException {
            if (containerPath.contains("!/")) {
                throw new IOException("Nested archive containers cannot themselves be nested: " + containerPath);
            }
            final EntryMetadata metadata = requirePlainContainer(containerPath);
            final JarEntry container = snapshot.byName().get(containerPath);
            final Path copy = Files.createTempFile(workspace, ".xjp-nested-cache-", ".jar");
            ownedCopies.add(copy);
            try {
                cancellation.check();
                final EntryFacts facts;
                try (InputStream source = ArchiveOperationSupport.guard(
                        snapshot.jar().getInputStream(container), cancellation);
                     java.io.OutputStream output = Files.newOutputStream(copy)) {
                    facts = ArchiveSupport.measure(source, output);
                }
                ArchiveCoverage.requireFacts(metadata, facts);
                cancellation.check();
                return new CachedContainer(copy);
            } catch (IOException | RuntimeException failure) {
                deleteFailedCopy(copy, failure);
                throw failure;
            }
        }

        /**
     * 子条目查找先于单条目解密，因此拒绝缺失或加密的容器。
         */
        private EntryMetadata requirePlainContainer(String containerPath) throws IOException {
            final EntryMetadata metadata = index.get(containerPath);
            final JarEntry container = snapshot.byName().get(containerPath);
            if (metadata == null || metadata.isEncrypted() || container == null || container.isDirectory()) {
                throw new IOException("Nested archive container is missing or encrypted: " + containerPath);
            }
            return metadata;
        }

        /**
     * 淘汰最近最少使用的空闲句柄，避免大量依赖耗尽进程文件描述符。
         */
        private void makeRoomForJar(CachedContainer opening) throws IOException {
            if (openJars < MAX_OPEN_JARS) {
                return;
            }
            for (CachedContainer candidate : containers.values()) {
                if (candidate != opening && candidate.jar != null && candidate.openLeases == 0) {
                    closeJar(candidate);
                    return;
                }
            }
            throw new IOException("Nested archive cache has no idle JarFile handle to evict");
        }

        /**
     * 句柄成功关闭前继续保留所有权，以便清理失败时重试。
         */
        @SuppressWarnings("PMD.CloseResource")
        private void closeJar(CachedContainer container) throws IOException {
            final JarFile jar = container.jar;
            jar.close();
            container.jar = null;
            openJars--;
        }

        /**
     * 串行执行释放，避免并发重复关闭错误地解除其他读取者的句柄固定。
         */
        private synchronized void release(CachedContainer container) {
            if (container.openLeases > 0) {
                container.openLeases--;
            }
        }

        /**
     * 所属受保护归档关闭后禁止再打开资源。
         */
        private void ensureOpen() throws IOException {
            if (closed) {
                throw new IOException("Nested archive cache is closed");
            }
        }

        /**
     * 关闭所有句柄并删除全部自有副本；报告错误前尽力完成每项清理。
         */
        @Override
        public synchronized void close() throws IOException {
            closed = true;
            IOException failure = null;
            for (CachedContainer container : containers.values()) {
                try {
                    if (container.jar != null) {
                        closeJar(container);
                    }
                } catch (IOException closeFailure) {
                    failure = mergeFailure(failure, closeFailure);
                }
            }
            final Iterator<Path> copies = ownedCopies.iterator();
            while (copies.hasNext()) {
                try {
                    Files.deleteIfExists(copies.next());
                    copies.remove();
                } catch (IOException deleteFailure) {
                    failure = mergeFailure(failure, deleteFailure);
                }
            }
            if (failure == null) {
                containers.clear();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * 调用方校验容器目录或读取子流期间持有一个缓存句柄。
     */
    static final class ContainerLease implements AutoCloseable {
        private final ContainerCache owner;
        private final CachedContainer container;
        private final JarFile archive;
        private boolean closed;

        /**
     * 在校验或子流读取完成前固定选中的 JarFile。
         */
        private ContainerLease(ContainerCache owner, CachedContainer container, JarFile archive) {
            this.owner = owner;
            this.container = container;
            this.archive = archive;
        }

        /**
     * 返回由外围缓存持有其认证私有副本的 JarFile。
         */
        JarFile archive() {
            return archive;
        }

        /**
     * 串行执行释放，避免并发重复关闭错误地解除其他读取者的句柄固定。
         */
        @Override
        public synchronized void close() {
            if (!closed) {
                owner.release(container);
                closed = true;
            }
        }
    }

    /**
     * 仅保留容器私有副本及可选的打开句柄，不保存解密后的应用条目。
     */
    private static final class CachedContainer {
        private final Path copy;
        private JarFile jar;
        private int openLeases;

        /**
     * 保存经过摘要校验且供容器中所有逻辑子路径共用的唯一副本。
         */
        private CachedContainer(Path copy) {
            this.copy = copy;
        }
    }

    /**
     * 将条目流生命周期与缓存租约关联，报告关闭失败时仍会释放句柄固定。
     */
    private static final class NestedEntryStream extends java.io.FilterInputStream {
        private final ContainerLease lease;
        private boolean closed;

        /**
     * 接管一个已认证子条目的流和容器租约。
         */
        private NestedEntryStream(InputStream input, ContainerLease lease) {
            super(input);
            this.lease = lease;
        }

        /**
     * 串行处理关闭调用，即使条目流自身关闭失败也始终释放句柄固定。
         */
        @Override
        @SuppressWarnings("PMD.UseTryWithResources")
        public synchronized void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                super.close();
            } finally {
                lease.close();
            }
        }
    }
}
