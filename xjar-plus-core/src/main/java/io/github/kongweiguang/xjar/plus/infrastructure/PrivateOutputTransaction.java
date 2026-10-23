/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.infrastructure.distribution.AtomicPublisher;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/**
 * 持有同文件系统内的私有工作区，并且只发布完整验证后的产物。
 */
public final class PrivateOutputTransaction implements AutoCloseable {
    private final Path output;
    private final Path workspace;
    private final Path stagedOutput;
    private final boolean directory;
    private boolean committed;
    private boolean closed;

    /**
     * 在目标旁边暂存；原子且不覆盖的发布可解决并发竞争，无需可能遗留的锁。
     */
    public PrivateOutputTransaction(final Path destination, final boolean outputDirectory) throws IOException {
        output = Objects.requireNonNull(destination, "output").toAbsolutePath().normalize();
        directory = outputDirectory;
        if (output.getFileName() == null || output.getParent() == null) {
            throw new IOException("An output must name a file or directory, not a filesystem root");
        }
        Files.createDirectories(output.getParent());
        rejectExistingOutput();
        Path created = null;
        try {
            rejectExistingOutput();
            created = createPrivateDirectory(output.getParent());
            workspace = created;
            stagedOutput = workspace.resolve("result");
            if (directory) {
                Files.createDirectory(stagedOutput);
            }
        } catch (IOException | RuntimeException failure) {
            cleanupAfterConstruction(created, failure);
            throw failure;
        }
    }

    /**
     * 新建目录接收任何含秘密内容前，先设置仅所有者可访问的权限。
     */
    public static void makePrivate(final Path path) throws IOException {
        final var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwx------" : "rw-------"));
            return;
        }
        final var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) {
            throw new IOException("Filesystem cannot provide private permissions for secret staging: " + path);
        }
        final AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner()).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        }
        acl.setAcl(List.of(entry.build()));
    }

    /**
     * 在支持的平台原子设置 POSIX 权限，并在返回目录前设置 Windows ACL。
     */
    private static Path createPrivateDirectory(final Path parent) throws IOException {
        final Path result;
        if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null) {
            result = Files.createTempDirectory(parent, ".xjp-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
            result = Files.createTempDirectory(parent, ".xjp-");
        }
        try {
            makePrivate(result);
            return result;
        } catch (IOException | RuntimeException failure) {
            try {
                Files.deleteIfExists(result);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * 为刚结束的 Windows 进程提供有界时间释放文件句柄，然后再重试。
     */
    private static void waitForHandles() throws IOException {
        try {
            Thread.sleep(150);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while cleaning private staging", failure);
        }
    }

    /**
     * 清理时不跟随链接，防止工具创建的链接将递归删除导向工作区外。
     */
    private static void deleteWorkspace(final Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            /** Deletes link objects and ordinary files alike, without touching their targets. */
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            /** Removes directories only after all owned children have been visited. */
            @Override
            public FileVisitResult postVisitDirectory(final Path path, final IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 仅暴露当前事务的私有目录，用于编译日志和临时秘密文件。
     */
    public Path workspace() {
        return workspace;
    }

    /**
     * 调用方生成并验证完整结果期间，保持最终公开名称不存在。
     */
    public Path stagedOutput() {
        return stagedOutput;
    }

    /**
     * 在同一文件系统内提交，不以可能暴露部分结果的复制操作作为回退。
     */
    public Path commit() throws IOException {
        if (closed || committed) {
            throw new IOException("Output transaction is already closed or committed");
        }
        rejectExistingOutput();
        if (directory != Files.isDirectory(stagedOutput, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(stagedOutput) || !Files.exists(stagedOutput, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Staged output has an unexpected type or is missing");
        }
        removeTransientFiles();
        AtomicPublisher.publish(stagedOutput, output);
        committed = true;
        return output;
    }

    /**
     * 即使收到取消也完成有界秘密清理，随后恢复调用方的中断状态。
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        final boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                try {
                    if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) {
                        deleteWorkspace(workspace);
                    }
                    closed = true;
                    return;
                } catch (IOException failure) {
                    if (attempt == 2) {
                        final String state = committed
                                ? "Output committed at " + output + "; " : "Output not committed; ";
                        throw new IOException(state + "could not remove private staging directory " + workspace,
                                failure);
                    }
                    waitForHandles();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 同样拒绝悬空符号链接，因为看似不存在的目标仍属于用户文件系统数据。
     */
    private void rejectExistingOutput() throws IOException {
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Output already exists; choose a new destination: " + output);
        }
    }

    /**
     * 保留原始异常，同时记录构造过程中已获取的所有路径以供清理。
     */
    private void cleanupAfterConstruction(final Path created, final Exception failure) {
        try {
            if (created != null) {
                deleteWorkspace(created);
            }
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 发布前移除含秘密的构建输入，使清理失败仍发生在尚未提交的一侧。
     */
    private void removeTransientFiles() throws IOException {
        try (var children = Files.list(workspace)) {
            final var iterator = children.iterator();
            while (iterator.hasNext()) {
                final Path child = iterator.next();
                if (!child.equals(stagedOutput)) {
                    if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        deleteWorkspace(child);
                    } else {
                        Files.delete(child);
                    }
                }
            }
        }
    }
}
