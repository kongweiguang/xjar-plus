/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.api;

import java.util.concurrent.CancellationException;

/**
 * 面向长时间归档和启动器操作的公开取消信号。
 */
public abstract class CancellationToken {
    public static final CancellationToken NONE = new CancellationToken() {
        /** Keeps the default request explicitly non-cancellable without allocating state. */
        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    /**
     * 返回所属 UI、CLI 或 API 调用方是否已请求取消。
     */
    public abstract boolean isCancelled();

    /**
     * 在安全操作边界将取消请求转换为明确的取消结果。
     */
    public void check() {
        if (isCancelled()) {
            throw new CancellationException("XJar Plus operation was cancelled");
        }
    }
}
