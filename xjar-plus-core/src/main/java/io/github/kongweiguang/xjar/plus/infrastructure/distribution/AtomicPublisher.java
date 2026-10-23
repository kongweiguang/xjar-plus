/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure.distribution;

import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.WString;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 在同一卷内发布完整条目，不覆盖任何并发进程创建的目标。
 */
public final class AtomicPublisher {
    private static final int AT_CURRENT_DIRECTORY = -100;
    private static final int LINUX_RENAME_NO_REPLACE = 1;
    private static final int MAC_RENAME_EXCLUSIVE = 4;
    private static final int POSIX_ALREADY_EXISTS = 17;
    private static final int WINDOWS_FILE_EXISTS = 80;
    private static final int WINDOWS_ALREADY_EXISTS = 183;

    /**
     * 将平台专属的重命名标志封装在唯一的发布操作之后。
     */
    private AtomicPublisher() {
    }

    /**
     * 使用内核提供的排他重命名，而不采用可能覆盖并发目标的“先检查、再 ATOMIC_MOVE”操作。
     * 跨卷移动或本机支持不可用时明确失败，不回退为复制、覆盖或删除。
     * 该操作保证命名空间发布，不保证断电持久性，也不防止父目录被替换。
     */
    public static void publish(final Path source, final Path destination) throws IOException {
        final Path from = localAbsolutePath(source);
        final Path to = localAbsolutePath(destination);
        if (from.equals(to) || Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            throw new FileAlreadyExistsException(to.toString());
        }
        try {
            if (Platform.isWindows()) {
                publishWindows(from, to);
            } else if (Platform.isLinux()) {
                publishPosix(from, to, true);
            } else if (Platform.isMac()) {
                publishPosix(from, to, false);
            } else {
                throw new AtomicMoveNotSupportedException(from.toString(), to.toString(),
                        "Atomic no-replace publication is unsupported on this operating system");
            }
        } catch (LinkageError | SecurityException unavailable) {
            final var failure = new AtomicMoveNotSupportedException(from.toString(), to.toString(),
                    "Native atomic no-replace publication is unavailable; no fallback was attempted");
            failure.initCause(unavailable);
            throw failure;
        }
    }

    /**
     * 在转换成本机路径前拒绝虚拟文件系统提供方，避免意外操作无关的本地文件。
     */
    private static Path localAbsolutePath(final Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (path.getFileSystem() != FileSystems.getDefault()) {
            throw new IOException("Atomic publication requires the default local filesystem provider: " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * 使用零标志，避免 MOVEFILE_REPLACE_EXISTING 或 MOVEFILE_COPY_ALLOWED 弱化提交边界。
     */
    private static void publishWindows(final Path source, final Path destination) throws IOException {
        final Function move = Function.getFunction("kernel32", "MoveFileExW", Function.ALT_CONVENTION);
        final int result = move.invokeInt(new Object[]{windowsPath(source), windowsPath(destination), 0});
        if (result == 0) {
            throw nativeFailure(source, destination, "MoveFileExW", Native.getLastError(), true);
        }
    }

    /**
     * 将并发竞争仲裁交给文件系统的“不覆盖”标志，而非 Java 锁或预检判断。
     */
    private static void publishPosix(final Path source, final Path destination, final boolean linux)
            throws IOException {
        final String name = linux ? "renameat2" : "renamex_np";
        final Function rename = Function.getFunction(Platform.C_LIBRARY_NAME, name,
                Function.C_CONVENTION, Native.getDefaultStringEncoding());
        final Object[] arguments = linux
                ? new Object[]{AT_CURRENT_DIRECTORY, source.toString(), AT_CURRENT_DIRECTORY,
                destination.toString(), LINUX_RENAME_NO_REPLACE}
                : new Object[]{source.toString(), destination.toString(), MAC_RENAME_EXCLUSIVE};
        if (rename.invokeInt(arguments) != 0) {
            throw nativeFailure(source, destination, name, Native.getLastError(), false);
        }
    }

    /**
     * 保留 UTF-16 和扩展长度路径（含 UNC 路径），不依赖进程启用 MAX_PATH 长路径选项。
     */
    private static WString windowsPath(final Path path) {
        final String value = path.toString();
        if (value.startsWith("\\\\?\\")) {
            return new WString(value);
        }
        if (value.startsWith("\\\\")) {
            return new WString("\\\\?\\UNC\\" + value.substring(2));
        }
        return new WString("\\\\?\\" + value);
    }

    /**
     * 立即读取本机错误并报告失败，不尝试可能具有破坏性的重试。
     */
    private static IOException nativeFailure(final Path source, final Path destination, final String operation,
                                             final int error, final boolean windows) {
        final String reason = operation + " refused atomic no-replace publication (native error " + error
                              + "); no fallback was attempted";
        final boolean exists = windows ? error == WINDOWS_FILE_EXISTS || error == WINDOWS_ALREADY_EXISTS
                : error == POSIX_ALREADY_EXISTS;
        return exists ? new FileAlreadyExistsException(source.toString(), destination.toString(), reason)
                : new FileSystemException(source.toString(), destination.toString(), reason);
    }
}
