/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 仅描述已提交的产物，不会把暂存路径作为成功结果暴露。
 */
public final class BuildResult {
    private final Path output;
    private final String artifactId;
    private final List<String> warnings;

    /**
     * 复制并冻结警告列表，避免调用方复用集合后改变已完成操作的结果。
     */
    public BuildResult(Path output, String artifactId, List<String> warnings) {
        this.output = Objects.requireNonNull(output, "output");
        this.artifactId = artifactId;
        this.warnings = List.copyOf(warnings);
    }

    /**
     * 返回已提交的文件或可执行程序路径，供脚本作为后续流水线输入。
     */
    public Path output() {
        return output;
    }

    /**
     * 若操作创建或检查了加密内容，则返回其格式层产物标识。
     */
    public String artifactId() {
        return artifactId;
    }

    /**
     * 返回不可变的非致命提示，CLI 和 UI 不应将其隐藏。
     */
    public List<String> warnings() {
        return warnings;
    }
}
