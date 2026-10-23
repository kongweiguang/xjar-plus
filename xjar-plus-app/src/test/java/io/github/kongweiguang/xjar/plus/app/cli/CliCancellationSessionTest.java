/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliCancellationSessionTest {
    /**
     * 验证正常结束只注销当前钩子，不取消或中断调用线程。
     */
    @Test
    void closeRemovesHookWithoutCancellingWorker() {
        final RecordingHookRegistry hooks = new RecordingHookRegistry();
        final Thread worker = Thread.currentThread();
        final CliCancellationSession session = new CliCancellationSession(worker, hooks, 100);

        session.close();
        session.close();

        assertNotNull(hooks.registered);
        assertTrue(hooks.removed);
        assertFalse(session.isCancelled());
        assertFalse(worker.isInterrupted());
    }

    /**
     * 验证关闭钩子只中断并等待当前命令工作线程，随后才观察到取消状态。
     */
    @Test
    void hookCancelsAndInterruptsOnlyItsWorker() throws InterruptedException {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final Thread worker = new Thread(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException exception) {
                interrupted.set(true);
            }
        }, "test-cli-worker");
        worker.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        final RecordingHookRegistry hooks = new RecordingHookRegistry();
        try (CliCancellationSession session = new CliCancellationSession(worker, hooks, 1_000)) {
            hooks.registered.run();

            assertTrue(session.isCancelled());
            assertThrows(java.util.concurrent.CancellationException.class, session::check);
            assertTrue(interrupted.get());
            assertFalse(worker.isAlive());
            assertFalse(Thread.currentThread().isInterrupted());
        }
        assertTrue(hooks.removed);
    }

    /**
     * 确保无响应的工作线程不会超过清理时限阻塞 JVM 关闭。
     */
    @Test
    void hookWaitIsBoundedWhenWorkerIgnoresInterruption() throws InterruptedException {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread worker = new Thread(() -> {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // 模拟不可中断的外部操作，直到测试主动释放工作线程。
                }
            }
        }, "test-cli-stuck-worker");
        worker.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        final RecordingHookRegistry hooks = new RecordingHookRegistry();
        final CliCancellationSession session = new CliCancellationSession(worker, hooks, 50);
        final long startedAt = System.nanoTime();
        hooks.registered.run();
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        release.countDown();
        worker.join(1_000);
        session.close();

        assertTrue(session.isCancelled());
        assertTrue(elapsedMillis < 1_000);
        assertFalse(worker.isAlive());
    }

    /**
     * 使单元测试可观察钩子的安装和移除，而不修改 JVM 全局关闭状态。
     */
    private static final class RecordingHookRegistry implements CliCancellationSession.ShutdownHookRegistry {
        private Thread registered;
        private boolean removed;

        /**
         * 保存待测钩子，使测试无需启动 JVM 关闭流程也能调用它。
         */
        @Override
        public void add(Thread hook) {
            registered = hook;
        }

        /**
         * 记录钩子移除，以便断言命令正常清理行为。
         */
        @Override
        public boolean remove(Thread hook) {
            removed = registered == hook;
            return removed;
        }
    }
}
