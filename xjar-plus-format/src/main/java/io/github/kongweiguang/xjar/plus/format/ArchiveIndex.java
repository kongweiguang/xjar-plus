/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 保存所有受保护归档条目的不可变元数据，并按确定顺序排列。
 *
 * @author kongweiguang
 */
public final class ArchiveIndex {
    private final List<EntryMetadata> entries;
    private final Map<String, EntryMetadata> byPath;

    /**
     * 按无符号 UTF-8 字节排序，确保 Go 与 Java 的序列化顺序不受区域设置影响。
     */
    public ArchiveIndex(Collection<EntryMetadata> entries) {
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        final List<EntryMetadata> ordered = new ArrayList<EntryMetadata>(entries.size());
        final Map<String, EntryMetadata> lookup = new HashMap<String, EntryMetadata>();
        for (EntryMetadata entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException("entries must not contain null");
            }
            if (lookup.put(entry.getPath(), entry) != null) {
                throw new IllegalArgumentException("duplicate archive entry path: " + entry.getPath());
            }
            ordered.add(entry);
        }
        if (ordered.size() > IndexCodec.MAX_ENTRIES) {
            throw new IllegalArgumentException("archive index contains too many entries");
        }
        ordered.sort((left, right) -> compareUnsigned(
                FormatSupport.utf8(left.getPath()), FormatSupport.utf8(right.getPath())));
        this.entries = Collections.unmodifiableList(ordered);
        this.byPath = Collections.unmodifiableMap(lookup);
    }

    /**
     * 按无符号值比较协议字节，与 Go 的逐字节排序保持一致。
     */
    private static int compareUnsigned(byte[] left, byte[] right) {
        final int limit = Math.min(left.length, right.length);
        for (int i = 0; i < limit; i++) {
            final int comparison = (left[i] & 0xff) - (right[i] & 0xff);
            if (comparison != 0) {
                return comparison;
            }
        }
        return left.length - right.length;
    }

    /**
     * 按原始路径精确查找，不规范化调用方输入，避免不同归档加载器之间出现别名。
     */
    public EntryMetadata get(String path) {
        return byPath.get(path);
    }

    /**
     * 返回索引线格式编码器使用的规范不可变顺序。
     */
    public List<EntryMetadata> entries() {
        return entries;
    }
}
