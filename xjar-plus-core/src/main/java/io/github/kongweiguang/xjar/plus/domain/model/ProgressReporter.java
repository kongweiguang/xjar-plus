/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.domain.model;

/**
 * 不依赖 CLI、浏览器或框架类型的领域进度端口。
 */
@FunctionalInterface
public interface ProgressReporter {
    /**
 * 报告一个有界阶段，使调用方展示进度时无需访问可变归档状态。
     */
    void report(String stage, int percent);
}
