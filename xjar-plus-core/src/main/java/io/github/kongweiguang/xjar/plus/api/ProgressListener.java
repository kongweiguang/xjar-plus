/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.api;

/**
 * 报告粗粒度操作进度，不让调用方依赖归档内部实现。
 */
@FunctionalInterface
public interface ProgressListener {
    /**
 * 接收面向用户的阶段名称和有界百分比，使 CLI 与 UI 共用同一进度契约。
     */
    void onProgress(String stage, int percent);
}
