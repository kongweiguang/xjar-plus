/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 持有一次校验后的 JarFile 视图，避免调用方混用不同归档读取中的条目。
 */
final class ArchiveSnapshot implements AutoCloseable {
    private final JarFile jar;
    private final List<JarEntry> entries;
    private final Map<String, JarEntry> byName;
    private final Facts facts;

    /**
     * ArchiveSupport 完成重复项和路径校验后冻结结构性结果。
     */
    ArchiveSnapshot(JarFile jar, List<JarEntry> entries, Map<String, JarEntry> byName, Facts facts) {
        this.jar = jar;
        this.entries = List.copyOf(entries);
        this.byName = Map.copyOf(byName);
        this.facts = facts;
    }

    /**
     * 返回与当前快照流对应的归档句柄。
     */
    JarFile jar() {
        return jar;
    }

    /**
     * 返回稳定的原始条目列表，避免运行时版本视图隐藏 Multi-Release 变体。
     */
    List<JarEntry> entries() {
        return entries;
    }

    /**
     * 重复名称已拒绝后，按原始名称查找归档条目。
     */
    Map<String, JarEntry> byName() {
        return byName;
    }

    /**
     * 返回已解析的当前 manifest；非可执行归档返回 null。
     */
    Manifest manifest() {
        return facts.manifest();
    }

    /**
     * 返回 manifest 的原始字节，以便保护后无损恢复。
     */
    byte[] manifestBytes() {
        final byte[] bytes = facts.manifestBytes();
        return bytes == null ? null : bytes.clone();
    }

    /**
     * 返回识别出的受支持归档家族。
     */
    ArchiveFamily family() {
        return facts.family();
    }

    /**
     * 对框架归档返回应用入口类，而非加载器入口类。
     */
    String applicationMainClass() {
        return facts.applicationMainClass();
    }

    /**
     * 返回是否存在 XJar Plus v2 元数据。
     */
    boolean encrypted() {
        return facts.encrypted();
    }

    /**
     * 返回基础条目中的最高 class 文件版本要求，不包括 MR 变体。
     */
    Integer minimumJava() {
        return facts.minimumJava();
    }

    /**
     * 条目流和归档写入全部结束后关闭 JarFile。
     */
    @Override
    public void close() throws IOException {
        jar.close();
    }

    /**
     * 将解析结果与自有 ZIP 句柄分开收集，使快照构造职责清晰。
     */
    record Facts(Manifest manifest, byte[] manifestBytes, ArchiveFamily family, String applicationMainClass,
                 boolean encrypted, Integer minimumJava) {
        /**
     * 仅复制一次 manifest，避免快照保留调用方可变字节数组的引用。
         */
        Facts {
            manifestBytes = manifestBytes == null ? null : manifestBytes.clone();
        }
    }
}
