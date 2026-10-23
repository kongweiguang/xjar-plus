/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.ChunkCipher;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import io.github.kongweiguang.xjar.plus.format.IndexCodec;
import io.github.kongweiguang.xjar.plus.format.KeyEnvelope;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.jar.JarEntry;

/**
 * 持有已解包的归档凭据，并在开放条目流前校验索引与容器的覆盖关系。
 */
final class ProtectedArchive implements AutoCloseable {
    private static final int MAX_INDEX_BYTES = IndexCodec.MAX_INDEX_BYTES + 32;

    private final String artifactId;
    private final byte[] dataKey;
    private final ArchiveIndex index;
    private final NestedArchiveContent.ContainerCache nestedContainers;

    /**
     * 将解包后的密钥和已校验嵌套缓存的生命周期转交给当前操作作用域。
     */
    private ProtectedArchive(String artifactId, byte[] dataKey, ArchiveIndex index,
                             NestedArchiveContent.ContainerCache nestedContainers) {
        this.artifactId = artifactId;
        this.dataKey = dataKey;
        this.index = index;
        this.nestedContainers = nestedContainers;
    }

    /**
     * 先认证密钥信封再解析索引，确保损坏元数据不会触发明文回退。
     */
    static ProtectedArchive open(ArchiveSnapshot snapshot, char[] password, java.nio.file.Path workspace,
                                 CancellationProbe cancellation) throws IOException {
        if (!snapshot.encrypted()) {
            throw new IOException("Input archive is not encrypted with XJar Plus v2");
        }
        byte[] dataKey = null;
        NestedArchiveContent.ContainerCache nestedContainers = null;
        try {
            final byte[] envelopeBytes = ArchiveSupport.readBounded(
                    snapshot.jar().getInputStream(snapshot.byName().get(ArchiveSupport.ENVELOPE)), 1024);
            final byte[] indexBytes = ArchiveSupport.readBounded(
                    snapshot.jar().getInputStream(snapshot.byName().get(ArchiveSupport.INDEX)), MAX_INDEX_BYTES);
            try (KeyEnvelope envelope = KeyEnvelope.parse(envelopeBytes)) {
                dataKey = envelope.unwrap(password);
                final ArchiveIndex index = IndexCodec.open(indexBytes, dataKey, envelope.getArtifactId());
                nestedContainers = new NestedArchiveContent.ContainerCache(snapshot, index, workspace, cancellation);
                ArchiveCoverage.validate(snapshot, index, nestedContainers, cancellation);
                return new ProtectedArchive(envelope.getArtifactId(), dataKey, index, nestedContainers);
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            cleanupFailedOpen(nestedContainers, dataKey, e);
            throw new IOException("Archive envelope or authenticated index validation failed", e);
        } catch (IOException | RuntimeException e) {
            cleanupFailedOpen(nestedContainers, dataKey, e);
            throw e;
        }
    }

    /**
     * 认证打开过程失败时释放已获取的部分缓存状态并清除秘密密钥字节。
     */
    private static void cleanupFailedOpen(NestedArchiveContent.ContainerCache nestedContainers,
                                          byte[] dataKey, Throwable failure) {
        if (nestedContainers != null) {
            try {
                nestedContainers.close();
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        if (dataKey != null) {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    /**
     * 返回作为每个加密条目认证上下文的产物标识。
     */
    String artifactId() {
        return artifactId;
    }

    /**
     * 仅向同包密码流提供密钥，绝不将其暴露给公开诊断信息。
     */
    byte[] dataKey() {
        return dataKey;
    }

    /**
     * 返回已认证且不可变的条目索引。
     */
    ArchiveIndex index() {
        return index;
    }

    /**
     * 打开原始或认证内容；加密块认证失败时保留带有 GCM 原因的 IOException。
     */
    InputStream openContent(ArchiveSnapshot snapshot, EntryMetadata metadata, java.nio.file.Path workspace)
            throws IOException {
        final String path = metadata.getPath();
        final int nestedSeparator = path.indexOf("!/");
        if (nestedSeparator >= 0) {
            if (path.indexOf("!/", nestedSeparator + 2) >= 0) {
                throw new IOException("Nested archive depth is unsupported: " + path);
            }
            return nestedContainers.openEntry(metadata, dataKey, artifactId);
        }
        final JarEntry entry = snapshot.byName().get(path);
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Authenticated archive entry is missing: " + path);
        }
        final InputStream raw = snapshot.jar().getInputStream(entry);
        if (!metadata.isEncrypted()) {
            return raw;
        }
        try {
            return ChunkCipher.decryptingStream(raw, dataKey, artifactId, path, metadata.getLength());
        } catch (IOException | RuntimeException failure) {
            raw.close();
            throw failure;
        }
    }

    /**
     * 操作不再持有认证流后立即清除密钥字节。
     */
    @Override
    public void close() throws IOException {
        try {
            nestedContainers.close();
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }
}
