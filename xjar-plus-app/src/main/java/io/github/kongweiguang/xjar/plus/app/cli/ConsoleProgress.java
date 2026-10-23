/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.ProgressListener;

/**
 * 进度写入 stderr，使 stdout 中的 JSON 结果仍可直接用于管道处理。
 */
public final class ConsoleProgress implements ProgressListener {
    /**
     * 在 stderr 输出有界进度文本，避免破坏成功结果的机器可读格式。
     */
    @Override
    public void onProgress(String stage, int percent) {
        final String safeStage = stage == null ? "处理中" : stage.replaceAll("[\\r\\n\\t]", " ");
        System.err.printf("\r%s %d%%%n", safeStage, Math.max(0, Math.min(100, percent)));
    }
}
