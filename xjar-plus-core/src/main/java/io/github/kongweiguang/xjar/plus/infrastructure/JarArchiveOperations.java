/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.ArchiveCommand;
import io.github.kongweiguang.xjar.plus.domain.model.ArchiveSummary;
import io.github.kongweiguang.xjar.plus.domain.model.BuildArtifact;
import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import io.github.kongweiguang.xjar.plus.domain.port.ArchiveOperations;
import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.ChunkCipher;
import io.github.kongweiguang.xjar.plus.format.Crypto;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import io.github.kongweiguang.xjar.plus.format.IndexCodec;
import io.github.kongweiguang.xjar.plus.format.KeyEnvelope;
import io.github.kongweiguang.xjar.plus.format.NonceSequence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * 执行认证式 JAR 转换和暂存发布的基础设施适配器。
 */
public final class JarArchiveOperations implements ArchiveOperations {
    private static final int MAX_INDEX_BYTES = IndexCodec.MAX_INDEX_BYTES + 32;
    private static final int MAX_PASSWORD_CHARS = 65_536;

    /**
     * 通过领域端口隔离 ZIP 和密码学实现细节。
     */
    public JarArchiveOperations() {
    }

    /**
     * 通过公开 verify 和 packageLauncher 共用的路径验证新暂存的密文。
     */
    private static void verifyArchive(Path path, char[] password, Path workspace,
                                      io.github.kongweiguang.xjar.plus.domain.model.ProgressReporter progress,
                                      io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe cancellation)
            throws IOException {
        try (ArchiveSnapshot snapshot = ArchiveSupport.open(path)) {
            verifyProtectedSnapshot(snapshot, password, workspace, progress, cancellation);
        }
    }

    /**
     * 打开密钥信封并校验索引条目，随后返回已认证的应用入口类。
     */
    private static ArchiveSummary verifyProtectedSnapshot(
            ArchiveSnapshot snapshot, char[] password, Path workspace,
            io.github.kongweiguang.xjar.plus.domain.model.ProgressReporter progress,
            CancellationProbe cancellation)
            throws IOException {
        try (ProtectedArchive protectedArchive = ProtectedArchive.open(snapshot, password, workspace, cancellation)) {
            final List<EntryMetadata> entries = protectedArchive.index().entries();
            int minimumJava = snapshot.family().minimumJava();
            for (int i = 0; i < entries.size(); i++) {
                cancellation.check();
                final EntryMetadata metadata = entries.get(i);
                try (PushbackInputStream content = new PushbackInputStream(ArchiveOperationSupport.guard(
                        protectedArchive.openContent(snapshot, metadata, workspace), cancellation), 8)) {
                    minimumJava = Math.max(minimumJava,
                            ArchiveVersionScanner.classFeatureVersion(metadata.getPath(), content));
                    final EntryFacts facts = ArchiveSupport.measure(content, null);
                    ArchiveOperationSupport.requireFacts(metadata, facts);
                }
                progress.report("Verifying authenticated entries",
                        ArchiveOperationSupport.percentage(i + 1, entries.size(), 5, 94));
            }
            final String mainClass = authenticatedApplicationMainClass(snapshot, protectedArchive, workspace,
                    cancellation);
            return new ArchiveSummary(snapshot.family().displayName(), mainClass, true,
                    snapshot.entries().size(), minimumJava);
        }
    }

    /**
     * 从已认证的原始 manifest 读取应用入口，不使用运行时包装入口。
     */
    private static String authenticatedApplicationMainClass(ArchiveSnapshot snapshot,
                                                            ProtectedArchive protectedArchive,
                                                            Path workspace,
                                                            CancellationProbe cancellation) throws IOException {
        final EntryMetadata metadata = protectedArchive.index().get(ArchiveSupport.ORIGINAL_MANIFEST);
        final byte[] bytes = readAuthenticated(snapshot, metadata, workspace, protectedArchive, cancellation,
                ArchiveSupport.MAX_MANIFEST_BYTES);
        final Manifest originalManifest = new Manifest(new ByteArrayInputStream(bytes));
        return ArchiveLayout.applicationMainClass(originalManifest, snapshot.family());
    }

    /**
     * 复制和索引原始文件前，先写入启动器 manifest 与加密后的原始 manifest。
     */
    private static void writeProtectedArchive(ArchiveSnapshot snapshot, PrivateOutputTransaction transaction,
                                              ArchiveCommand command, ArchiveSelection selection,
                                              KeyEnvelope envelope, byte[] dataKey) throws IOException {
        final List<EntryMetadata> index = new ArrayList<>();
        final NonceSequence nonces = new NonceSequence();
        final ArchiveTransform transform = new ArchiveTransform(selection, command.encryptDependencies(), dataKey,
                envelope.getArtifactId(), nonces, index, command);
        final byte[] originalManifest = ArchiveSupport.originalManifest(snapshot);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(transaction.stagedOutput()))) {
            writeEntryBytes(output, ArchiveSupport.MANIFEST,
                    ArchiveSupport.launcherManifest(snapshot.manifest(), snapshot.family()));
            writeEncryptedBytes(output, ArchiveSupport.ORIGINAL_MANIFEST, originalManifest, dataKey,
                    envelope.getArtifactId(), nonces, index);
            final List<JarEntry> entries = snapshot.entries();
            for (int i = 0; i < entries.size(); i++) {
                command.cancellation().check();
                final JarEntry entry = entries.get(i);
                if (entry.isDirectory() || ArchiveSupport.MANIFEST.equals(entry.getName())) {
                    if (entry.isDirectory()) {
                        output.putNextEntry(ArchiveSupport.outputEntry(entry));
                        output.closeEntry();
                    }
                    continue;
                }
                if (ArchiveSupport.isNestedJar(entry.getName(), snapshot.family())) {
                    NestedJarOperations.writeContainer(snapshot, entry, entry.getName(), output,
                            transaction.workspace(), transform);
                } else {
                    writeOriginalEntry(snapshot, entry, output, transform);
                }
                command.progress().report("Encrypting archive entries",
                        ArchiveOperationSupport.percentage(i + 1, entries.size(), 4, 82));
            }
            RuntimePayload.inject(snapshot.family(), output, index);
            final byte[] sealedIndex;
            try {
                sealedIndex = IndexCodec.seal(new ArchiveIndex(index), dataKey, envelope.getArtifactId(), nonces);
            } catch (GeneralSecurityException | IllegalArgumentException failure) {
                throw new IOException("Unable to create authenticated archive index", failure);
            }
            if (sealedIndex.length > MAX_INDEX_BYTES) {
                throw new IOException("Authenticated archive index exceeds the supported limit");
            }
            writeEntryBytes(output, ArchiveSupport.ENVELOPE, envelope.encode());
            writeEntryBytes(output, ArchiveSupport.INDEX, sealedIndex);
        } finally {
            Arrays.fill(originalManifest, (byte) 0);
        }
    }

    /**
     * 对应用路径应用筛选规则，同时保留框架引导条目。
     */
    private static void writeOriginalEntry(ArchiveSnapshot snapshot, JarEntry entry, JarOutputStream output,
                                           ArchiveTransform transform) throws IOException {
        final String path = entry.getName();
        final boolean encrypt = transform.selection().selected(path)
                                && !ArchiveOperationSupport.isNativeBootstrapEntry(path, snapshot.family());
        final EntryFacts facts;
        if (encrypt) {
            try (InputStream input = snapshot.jar().getInputStream(entry)) {
                facts = ArchiveSupport.measure(ArchiveOperationSupport.guard(
                        input, transform.command().cancellation()), null);
            }
            output.putNextEntry(ArchiveSupport.outputEntry(entry));
            try (InputStream input = snapshot.jar().getInputStream(entry)) {
                ChunkCipher.encrypt(ArchiveOperationSupport.guard(input, transform.command().cancellation()),
                        output, transform.key(), transform.artifactId(), path, facts.length(), transform.nonces());
            } catch (GeneralSecurityException failure) {
                throw new IOException("Unable to encrypt archive entry: " + path, failure);
            }
        } else {
            output.putNextEntry(ArchiveSupport.outputEntry(entry));
            try (InputStream input = snapshot.jar().getInputStream(entry)) {
                facts = ArchiveSupport.measure(ArchiveOperationSupport.guard(
                        input, transform.command().cancellation()), output);
            }
        }
        output.closeEntry();
        transform.index().add(ArchiveSupport.metadata(path, encrypt, facts));
    }

    /**
     * 恢复原始条目字节，并移除注入的运行时类和全部 v2 元数据记录。
     */
    private static void writeRestoredArchive(ArchiveSnapshot snapshot, Path outputPath, Path workspace,
                                             ProtectedArchive protectedArchive, ArchiveCommand command)
            throws IOException {
        final List<EntryMetadata> outputIndex = new ArrayList<>();
        final EntryMetadata originalManifestMetadata = protectedArchive.index().get(ArchiveSupport.ORIGINAL_MANIFEST);
        final byte[] manifest = readAuthenticated(snapshot, originalManifestMetadata, workspace, protectedArchive,
                command.cancellation(), ArchiveSupport.MAX_MANIFEST_BYTES);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(outputPath))) {
            writeEntryBytes(output, ArchiveSupport.MANIFEST, manifest);
            for (JarEntry entry : snapshot.entries()) {
                command.cancellation().check();
                final String path = entry.getName();
                if (entry.isDirectory()) {
                    if (!ArchiveOperationSupport.isInjectedPath(path)) {
                        output.putNextEntry(ArchiveSupport.outputEntry(entry));
                        output.closeEntry();
                    }
                    continue;
                }
                if (ArchiveSupport.MANIFEST.equals(path) || ArchiveSupport.ENVELOPE.equals(path)
                    || ArchiveSupport.INDEX.equals(path) || ArchiveSupport.ORIGINAL_MANIFEST.equals(path)
                    || ArchiveOperationSupport.isInjectedPath(path)) {
                    continue;
                }
                final EntryMetadata metadata = protectedArchive.index().get(path);
                if (metadata == null) {
                    throw new IOException("Archive entry is absent from authenticated index: " + path);
                }
                if (ArchiveSupport.isNestedJar(path, snapshot.family())) {
                    NestedJarOperations.writeDecryptedContainer(snapshot, entry, path, output, workspace,
                            protectedArchive, outputIndex, command.cancellation());
                } else {
                    writeDecryptedEntry(snapshot, entry, metadata, output, workspace, protectedArchive, command);
                }
            }
        } finally {
            Arrays.fill(manifest, (byte) 0);
        }
    }

    /**
     * 解密单个外层条目，并在关闭输出 ZIP 条目前校验其认证摘要。
     */
    private static void writeDecryptedEntry(ArchiveSnapshot snapshot, JarEntry entry, EntryMetadata metadata,
                                            JarOutputStream output, Path workspace,
                                            ProtectedArchive protectedArchive, ArchiveCommand command)
            throws IOException {
        output.putNextEntry(ArchiveSupport.outputEntry(entry));
        try (InputStream content = protectedArchive.openContent(snapshot, metadata, workspace)) {
            ArchiveOperationSupport.requireFacts(metadata, ArchiveSupport.measure(
                    ArchiveOperationSupport.guard(content, command.cancellation()), output));
        }
        output.closeEntry();
    }

    /**
     * 通过分块解码器读取重定位后的 manifest，并执行小文件安全上限校验。
     */
    private static byte[] readAuthenticated(ArchiveSnapshot snapshot, EntryMetadata metadata, Path workspace,
                                            ProtectedArchive protectedArchive,
                                            CancellationProbe cancellation,
                                            int limit) throws IOException {
        if (metadata == null || metadata.getLength() > limit) {
            throw new IOException("Authenticated original manifest exceeds the supported size");
        }
        try (InputStream input = ArchiveOperationSupport.guard(
                protectedArchive.openContent(snapshot, metadata, workspace), cancellation);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) {
                    throw new IOException("Authenticated original manifest exceeds the supported size");
                }
                output.write(buffer, 0, count);
            }
            final byte[] bytes = output.toByteArray();
            ArchiveOperationSupport.requireFacts(metadata, ArchiveSupport.measure(
                    new ByteArrayInputStream(bytes), null));
            return bytes;
        }
    }

    /**
     * 将原始 manifest 与生成的启动器 manifest 分开加密。
     */
    private static void writeEncryptedBytes(JarOutputStream output, String path, byte[] bytes, byte[] key,
                                            String artifactId, NonceSequence nonces,
                                            List<EntryMetadata> index) throws IOException {
        final EntryFacts facts = ArchiveSupport.measure(new ByteArrayInputStream(bytes), null);
        output.putNextEntry(new JarEntry(path));
        try {
            ChunkCipher.encrypt(new ByteArrayInputStream(bytes), output, key, artifactId, path,
                    facts.length(), nonces);
        } catch (GeneralSecurityException failure) {
            throw new IOException("Unable to encrypt archive metadata: " + path, failure);
        }
        output.closeEntry();
        index.add(ArchiveSupport.metadata(path, true, facts));
    }

    /**
     * 在固定位置和时间戳写入有界的生成元数据条目。
     */
    private static void writeEntryBytes(JarOutputStream output, String path, byte[] bytes) throws IOException {
        final JarEntry entry = new JarEntry(path);
        entry.setTime(0L);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    /**
     * 创建新的 v2 密钥信封；Java 提供方不支持所需 AES-256 时明确失败。
     */
    private static KeyEnvelope createEnvelope(char[] password) throws IOException {
        try {
            final KeyEnvelope envelope = KeyEnvelope.create(password);
            if (Crypto.KEY_BYTES != 32) {
                throw new IOException("AES-256 is unavailable; XJar Plus does not downgrade its encryption suite");
            }
            return envelope;
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IOException("Unable to create the AES-256-GCM key envelope", failure);
        }
    }

    /**
     * 创建暂存区或调用密码算法前，先拒绝输入输出别名及格式错误的密码。
     */
    private static void validateWriteCommand(ArchiveCommand command) throws IOException {
        if (command == null || command.output() == null) {
            throw new IllegalArgumentException("archive command and output are required");
        }
        final Path input = command.input().toAbsolutePath().normalize();
        final Path output = command.output().toAbsolutePath().normalize();
        if (input.equals(output) || Files.exists(output) && Files.isSameFile(input, output)) {
            throw new IOException("Input and output must be different paths");
        }
        final char[] password = command.password();
        try {
            if (password.length == 0 || password.length > MAX_PASSWORD_CHARS) {
                throw new IllegalArgumentException("password must contain 1 to 65536 characters");
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 返回有界结构信息，全程不要求输入密码。
     */
    @Override
    public ArchiveSummary inspect(Path input) throws IOException {
        return ArchiveSupport.inspect(input);
    }

    /**
     * 生成新的数据密钥、流式处理选定条目、验证暂存产物，最后原子发布。
     */
    @Override
    public BuildArtifact encrypt(ArchiveCommand command) throws IOException {
        validateWriteCommand(command);
        final ArchiveSelection selection = new ArchiveSelection(command.includes(), command.excludes());
        final char[] password = command.password();
        try (ArchiveSnapshot snapshot = ArchiveSupport.open(command.input())) {
            if (snapshot.encrypted()) {
                throw new IOException("Input JAR is already protected; use the original JAR to create a new artifact");
            }
            ArchiveSupport.requireExecutable(snapshot);
            ArchiveSupport.rejectSigned(snapshot);
            command.cancellation().check();
            try (PrivateOutputTransaction transaction = new PrivateOutputTransaction(command.output(), false)) {
                try (KeyEnvelope envelope = createEnvelope(password)) {
                    final byte[] dataKey = envelope.getDataKey();
                    try {
                        command.progress().report("Encrypting archive entries", 4);
                        writeProtectedArchive(snapshot, transaction, command, selection, envelope, dataKey);
                        verifyArchive(transaction.stagedOutput(), password, transaction.workspace(),
                                command.progress(), command.cancellation());
                        command.cancellation().check();
                        command.progress().report("Committing verified archive", 98);
                        final Path result = transaction.commit();
                        command.progress().report("Complete", 100);
                        return new BuildArtifact(result, envelope.getArtifactId(), List.of());
                    } finally {
                        Arrays.fill(dataKey, (byte) 0);
                    }
                }
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 每个内容条目都通过认证后，才原子提交恢复出的 JAR。
     */
    @Override
    public BuildArtifact decrypt(ArchiveCommand command) throws IOException {
        validateWriteCommand(command);
        final char[] password = command.password();
        try (ArchiveSnapshot snapshot = ArchiveSupport.open(command.input());
             ArchiveOperationSupport.PrivateWorkspace workspace = ArchiveOperationSupport.PrivateWorkspace.create()) {
            try (ProtectedArchive protectedArchive = ProtectedArchive.open(snapshot, password, workspace.path(),
                    command.cancellation())) {
                command.cancellation().check();
                try (PrivateOutputTransaction transaction = new PrivateOutputTransaction(command.output(), false)) {
                    writeRestoredArchive(snapshot, transaction.stagedOutput(), workspace.path(),
                            protectedArchive, command);
                    try (ArchiveSnapshot restored = ArchiveSupport.open(transaction.stagedOutput())) {
                        if (restored.encrypted()) {
                            throw new IOException("Restored archive still contains XJar Plus metadata");
                        }
                        ArchiveSupport.requireExecutable(restored);
                    }
                    command.cancellation().check();
                    command.progress().report("Committing restored archive", 98);
                    final Path result = transaction.commit();
                    command.progress().report("Complete", 100);
                    return new BuildArtifact(result, protectedArchive.artifactId(), List.of());
                }
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 认证归档条目，并返回原始 manifest 中的应用入口。
     */
    @Override
    public ArchiveSummary verify(ArchiveCommand command) throws IOException {
        if (command == null) {
            throw new IllegalArgumentException("archive command is required");
        }
        final char[] password = command.password();
        try (ArchiveSnapshot snapshot = ArchiveSupport.open(command.input());
             ArchiveOperationSupport.PrivateWorkspace workspace = ArchiveOperationSupport.PrivateWorkspace.create()) {
            return verifyProtectedSnapshot(snapshot, password, workspace.path(), command.progress(),
                    command.cancellation());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

}
