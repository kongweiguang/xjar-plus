/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;

/**
 * 只编译一次可重复使用的 glob 策略，使所有嵌套归档遵循同一筛选规则。
 */
final class ArchiveSelection {
    private final List<java.nio.file.PathMatcher> includes;
    private final List<java.nio.file.PathMatcher> excludes;

    /**
 * 在打开输出事务前拒绝无效规则，使用户输入错误不产生副作用。
     */
    ArchiveSelection(List<String> includes, List<String> excludes) {
        final FileSystem fileSystem = FileSystems.getDefault();
        this.includes = includes.stream().map(pattern -> fileSystem.getPathMatcher("glob:" + pattern)).toList();
        this.excludes = excludes.stream().map(pattern -> fileSystem.getPathMatcher("glob:" + pattern)).toList();
    }

    /**
 * 最后应用排除规则，确保拒绝规则优先于范围更广的包含 glob。
     */
    boolean selected(String archivePath) {
        final Path path = Path.of(archivePath);
        final boolean included = includes.isEmpty() || includes.stream().anyMatch(matcher -> matcher.matches(path));
        return included && excludes.stream().noneMatch(matcher -> matcher.matches(path));
    }
}
