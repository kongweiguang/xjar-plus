/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.ArchiveCommand;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import io.github.kongweiguang.xjar.plus.format.NonceSequence;

import java.util.List;

/**
 * 在外层和嵌套归档写入器之间共用一个不可变转换上下文。
 */
record ArchiveTransform(ArchiveSelection selection, boolean encryptDependencies, byte[] key, String artifactId,
                        NonceSequence nonces, List<EntryMetadata> index, ArchiveCommand command) {
}
