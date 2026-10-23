/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.domain.model;

import java.nio.file.Path;
import java.util.List;

/**
 * 输出事务完成后的领域结果，永远不表示临时暂存路径。
 */
public final class BuildArtifact {
    private final Path output;
    private final String artifactId;
    private final List<String> warnings;

    /**
     * 在边界复制警告列表，使成功操作的结果保持稳定。
     */
    public BuildArtifact(Path output, String artifactId, List<String> warnings) {
        this.output = output;
        this.artifactId = artifactId;
        this.warnings = List.copyOf(warnings);
    }

    /**
     * 返回已提交的输出路径。
     */
    public Path output() {
        return output;
    }

    /**
     * 返回 v2 产物标识；发行凭据和许可证文件返回 null。
     */
    public String artifactId() {
        return artifactId;
    }

    /**
     * 返回预检期间收集的不可变警告。
     */
    public List<String> warnings() {
        return warnings;
    }
}
