/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.domain.model;

import java.util.concurrent.CancellationException;

/**
 * 领域取消端口，供归档和进程适配器在安全边界停止工作。
 */
@FunctionalInterface
public interface CancellationProbe {
    CancellationProbe NEVER = () -> false;

    /**
     * 返回所属用户操作是否已请求取消。
     */
    boolean cancelled();

    /**
     * 在开始新条目或进程前以统一结果响应取消请求。
     */
    default void check() {
        if (cancelled()) {
            throw new CancellationException("XJar Plus operation was cancelled");
        }
    }
}
