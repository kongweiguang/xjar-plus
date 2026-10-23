/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.domain.port;

import io.github.kongweiguang.xjar.plus.domain.model.ArchiveCommand;
import io.github.kongweiguang.xjar.plus.domain.model.ArchiveSummary;
import io.github.kongweiguang.xjar.plus.domain.model.BuildArtifact;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 归档操作端口使领域用例不依赖 ZIP 和密码学适配器。
 */
public interface ArchiveOperations {
    /**
     * 检查输入归档，不写入输出。
     */
    ArchiveSummary inspect(Path input) throws IOException;

    /**
     * 加密选定的应用内容，并提交一个新的归档。
     */
    BuildArtifact encrypt(ArchiveCommand command) throws IOException;

    /**
     * 恢复加密归档，只有所有受保护数据通过认证后才提交结果。
     */
    BuildArtifact decrypt(ArchiveCommand command) throws IOException;

    /**
     * 认证加密归档，但不发布明文文件。
     */
    ArchiveSummary verify(ArchiveCommand command) throws IOException;
}
