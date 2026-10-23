/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.CancellationToken;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 将一次 CLI 操作绑定到关闭钩子，但不接管标准输入或无关进程。
 */
public final class CliCancellationSession extends CancellationToken implements AutoCloseable {
    private static final long CLEANUP_WAIT_MILLIS = TimeUnit.SECONDS.toMillis(15);
    private static final AtomicBoolean SHUTDOWN_STARTED = new AtomicBoolean();
    private static final ShutdownHookRegistry JVM_HOOKS = new ShutdownHookRegistry() {
        /** 仅注册当前操作的关闭钩子，以便命令正常结束后将其移除。 */
        @Override
        public void add(Thread hook) {
            Runtime.getRuntime().addShutdownHook(hook);
        }

        /** 移除此会话的关闭钩子，避免已完成命令的状态残留在 JVM 中。 */
        @Override
        public boolean remove(Thread hook) {
            return Runtime.getRuntime().removeShutdownHook(hook);
        }
    };

    private final Thread worker;
    private final ShutdownHookRegistry hooks;
    private final Thread shutdownHook;
    private final long cleanupWaitMillis;
    private final AtomicBoolean shutdownRequested;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 记录当前命令线程；核心操作仅在安全事务边界协作取消，因此只中断该线程。
     */
    public CliCancellationSession() {
        this(Thread.currentThread(), JVM_HOOKS, CLEANUP_WAIT_MILLIS, SHUTDOWN_STARTED);
    }

    /**
     * 注入关闭钩子注册器和等待上限，使测试无需退出 JVM 也能验证关闭流程。
     */
    CliCancellationSession(Thread worker, ShutdownHookRegistry hooks, long cleanupWaitMillis) {
        this(worker, hooks, cleanupWaitMillis, new AtomicBoolean());
    }

    /**
     * 将进程关闭状态与测试会话隔离，同时保留可观察的生产入口关闭标记。
     */
    private CliCancellationSession(Thread worker, ShutdownHookRegistry hooks, long cleanupWaitMillis,
                                   AtomicBoolean shutdownRequested) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.hooks = Objects.requireNonNull(hooks, "hooks");
        this.shutdownRequested = Objects.requireNonNull(shutdownRequested, "shutdownRequested");
        if (cleanupWaitMillis < 1) {
            throw new IllegalArgumentException("cleanupWaitMillis must be positive");
        }
        this.cleanupWaitMillis = cleanupWaitMillis;
        shutdownHook = new Thread(this::cancelAndAwait, "xjar-plus-cli-shutdown");
        hooks.add(shutdownHook);
    }

    /**
     * 暴露关闭请求状态，避免关闭钩子执行期间 main 再次调用 System.exit。
     */
    public static boolean shutdownStarted() {
        return SHUTDOWN_STARTED.get();
    }

    /**
     * 返回共享核心用例读取的本次操作取消状态。
     */
    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * 标记进程正在关闭，并且只中断执行当前命令的工作线程。
     */
    private void cancelAndAwait() {
        shutdownRequested.set(true);
        cancelled.set(true);
        worker.interrupt();
        if (worker == Thread.currentThread()) {
            return;
        }
        try {
            worker.join(cleanupWaitMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 命令正常结束后移除钩子，并兼容 JVM 已进入关闭流程的情况。
     */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                hooks.remove(shutdownHook);
            } catch (IllegalStateException shutdownInProgress) {
                // JVM 已进入关闭流程，活动钩子负责有界清理等待，此时无法再移除钩子。
                shutdownRequested.set(true);
                cancelled.set(true);
            }
        }
    }

    /**
     * 允许测试替换 JVM 钩子安装行为，而不注册进程级真实钩子。
     */
    interface ShutdownHookRegistry {
        /**
         * 安装一个由当前会话负责的关闭钩子。
         */
        void add(Thread hook);

        /**
         * 移除之前安装的钩子，并返回它是否仍处于注册状态。
         */
        boolean remove(Thread hook);
    }
}
