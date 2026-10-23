/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;

/**
 * 汇集多个归档操作共用的策略，避免用例适配器依赖一个职责过大的工具类。
 */
final class ArchiveOperationSupport {
    private static final String RUNTIME_PREFIX = "io/github/kongweiguang/xjar/plus/runtime/";
    private static final String FORMAT_PREFIX = "io/github/kongweiguang/xjar/plus/format/";

    /**
 * 保持操作辅助逻辑无状态；临时工作区所有权由其作用域值对象表示。
     */
    private ArchiveOperationSupport() {
    }

    /**
 * 保留框架启动器和嵌套依赖容器的可读性，直到对应运行时适配器开始处理。
     */
    static boolean isNativeBootstrapEntry(String path, ArchiveFamily family) {
        if (ArchiveSupport.isNestedJar(path, family)) {
            return true;
        }
        if ((family == ArchiveFamily.BOOT_CLASSIC || family == ArchiveFamily.BOOT_MODERN)
            && (path.startsWith("org/springframework/boot/loader/")
                || path.equals("BOOT-INF/classpath.idx") || path.equals("BOOT-INF/layers.idx"))) {
            return true;
        }
        return family == ArchiveFamily.SOLON && path.startsWith("org/noear/solon/loader/");
    }

    /**
 * 恢复原始 JAR 时仅移除注入的框架运行时类和格式类。
     */
    static boolean isInjectedPath(String path) {
        return path.startsWith(RUNTIME_PREFIX) || path.startsWith(FORMAT_PREFIX);
    }

    /**
 * 内容被视为完整归档条目前，先比对认证长度、CRC 和 SHA-256。
     */
    static void requireFacts(EntryMetadata metadata, EntryFacts facts) throws IOException {
        if (metadata == null || metadata.getLength() != facts.length() || metadata.getCrc32() != facts.crc32()
            || !MessageDigest.isEqual(metadata.getSha256(), facts.sha256())) {
            final String path = metadata == null ? "<unknown>" : metadata.getPath();
            throw new IOException("Archive content does not match its authenticated index: " + path);
        }
    }

    /**
 * 每次有界读取时检查取消信号，使大型归档转换仍可中断。
     */
    static InputStream guard(InputStream input, CancellationProbe cancellation) {
        return new java.io.FilterInputStream(input) {
            /** Checks cancellation before a single-byte read without changing the wrapped stream's semantics. */
            @Override
            public int read() throws IOException {
                cancellation.check();
                return super.read();
            }

            /** Checks cancellation before a bulk read so large resources remain responsive. */
            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                cancellation.check();
                return super.read(bytes, offset, length);
            }
        };
    }

    /**
 * 将已完成条目数映射到有界阶段范围，并将空归档视为已完成。
     */
    static int percentage(int completed, int total, int minimum, int maximum) {
        if (total <= 0) {
            return maximum;
        }
        return minimum + (int) ((long) (maximum - minimum) * completed / total);
    }

    /**
 * 持有仅用于验证的嵌套副本，并且只删除私有根目录下由自身创建的路径。
     */
    static final class PrivateWorkspace implements AutoCloseable {
        private final Path path;

        /**
 * 将清理范围绑定到复制归档数据前创建的私有目录。
         */
        private PrivateWorkspace(Path path) {
            this.path = path;
        }

        /**
 * 创建仅所有者可访问的暂存区；权限设置失败时立即移除该目录。
         */
        static PrivateWorkspace create() throws IOException {
            final Path path = Files.createTempDirectory(".xjp-verify-");
            try {
                PrivateOutputTransaction.makePrivate(path);
                return new PrivateWorkspace(path);
            } catch (IOException failure) {
                Files.deleteIfExists(path);
                throw failure;
            }
        }

        /**
 * 返回验证操作可用于暂存嵌套条目的唯一目录。
         */
        Path path() {
            return path;
        }

        /**
 * 删除文件和链接时不跟随链接访问自有工作区以外的内容。
         */
        @Override
        public void close() throws IOException {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                /** Deletes only the encountered file or link itself, not the link target. */
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                /** Removes each directory after its owned children have been deleted. */
                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
