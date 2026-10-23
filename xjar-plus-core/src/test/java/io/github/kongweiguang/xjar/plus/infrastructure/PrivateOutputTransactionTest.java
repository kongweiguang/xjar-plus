/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出失败测试不依赖编译器、归档适配器或原生应用窗口。
 */
final class PrivateOutputTransactionTest {
    @TempDir
    private Path temporary;

    /**
     * 操作失败后既不能留下看似成功的产物，也不能残留临时秘密文件。
     */
    @Test
    void rollbackRemovesOnlyOwnedWorkspace() throws Exception {
        final Path output = temporary.resolve("result.jar");
        final Path neighbor = Files.writeString(temporary.resolve("keep.txt"), "keep");
        final Path stage;
        try (var transaction = new PrivateOutputTransaction(output, false)) {
            stage = transaction.workspace();
            Files.writeString(transaction.stagedOutput(), "partial secret");
        }
        assertFalse(Files.exists(stage));
        assertFalse(Files.exists(output));
        assertEquals("keep", Files.readString(neighbor));
        assertFalse(Files.exists(temporary.resolve(".result.jar.xjp-lock")));
    }

    /**
     * 即使准备过程已并发执行，提交时若目标刚被创建也必须失败。
     */
    @Test
    void refusesExistingAndConcurrentDestinations() throws Exception {
        final Path output = temporary.resolve("result.jar");
        try (var transaction = new PrivateOutputTransaction(output, false)) {
            Files.writeString(transaction.stagedOutput(), "new");
            Files.writeString(output, "existing");
            assertThrows(IOException.class, transaction::commit);
            assertEquals("existing", Files.readString(output));
        }
        assertThrows(IOException.class, () -> new PrivateOutputTransaction(output, false));
    }

    /**
     * 并发构建可各自准备产物，但指定名称只能由一个构建成功发布。
     */
    @Test
    void onlyOneConcurrentTransactionCanCommit() throws Exception {
        final Path output = temporary.resolve("winner.jar");
        try (var first = new PrivateOutputTransaction(output, false);
             var second = new PrivateOutputTransaction(output, false)) {
            Files.writeString(first.stagedOutput(), "first");
            Files.writeString(second.stagedOutput(), "second");
            first.commit();
            assertThrows(IOException.class, second::commit);
            assertEquals("first", Files.readString(output));
        }
    }

    /**
     * 文件或目录事务完成后执行暂存清理时，已提交内容必须保留。
     */
    @Test
    void commitsFilesAndDirectoriesOnlyOnce() throws Exception {
        final Path output = temporary.resolve("file.jar");
        try (var transaction = new PrivateOutputTransaction(output, false)) {
            Files.writeString(transaction.stagedOutput(), "complete");
            assertEquals(output, transaction.commit());
            assertThrows(IOException.class, transaction::commit);
        }
        assertEquals("complete", Files.readString(output));
        final Path directory = temporary.resolve("distribution");
        try (var transaction = new PrivateOutputTransaction(directory, true)) {
            Files.writeString(transaction.stagedOutput().resolve("launcher"), "complete");
            transaction.commit();
        }
        assertTrue(Files.isDirectory(directory));
        assertEquals("complete", Files.readString(directory.resolve("launcher")));
    }

    /**
     * 发布前必须移除编译中间件和私有配置，不能依赖后续 close 才发现并清理。
     */
    @Test
    void removesSecretsBeforeOutputBecomesVisible() throws Exception {
        final Path output = temporary.resolve("published.jar");
        try (var transaction = new PrivateOutputTransaction(output, false)) {
            final Path cache = Files.createDirectory(transaction.workspace().resolve("go-cache"));
            Files.writeString(cache.resolve("object"), "test secret material");
            Files.writeString(transaction.workspace().resolve("config.json"), "test secret configuration");
            Files.writeString(transaction.stagedOutput(), "complete");
            transaction.commit();
            try (var remaining = Files.list(transaction.workspace())) {
                assertEquals(0, remaining.count());
            }
            assertEquals("complete", Files.readString(output));
        }
    }

    /**
     * 取消清理不得吞掉中断信号，也不得遗弃事务持有的秘密目录。
     */
    @Test
    void cleanupPreservesCancellationSignal() throws Exception {
        final Path output = temporary.resolve("cancelled.jar");
        final Path stage;
        try {
            try (var transaction = new PrivateOutputTransaction(output, false)) {
                stage = transaction.workspace();
                Files.writeString(transaction.stagedOutput(), "test secret");
                Thread.currentThread().interrupt();
            }
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(stage));
            assertFalse(Files.exists(output));
        } finally {
            Thread.interrupted();
        }
    }
}
