/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.api;

import java.util.Objects;

/**
 * 对外提供稳定的归档摘要用于诊断，不暴露解析器实现类型。
 */
public final class ArchiveInfo {
    private final String kind;
    private final String mainClass;
    private final boolean encrypted;
    private final int entries;
    private final Integer minimumJava;

    /**
     * 仅固定公开 API 承诺的字段，使归档实现可以独立演进。
     */
    public ArchiveInfo(String kind, String mainClass, boolean encrypted, int entries, Integer minimumJava) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.mainClass = mainClass;
        this.encrypted = encrypted;
        this.entries = entries;
        this.minimumJava = minimumJava;
    }

    /**
     * 返回 CLI 诊断和兼容性报告使用的归档家族标识。
     */
    public String kind() {
        return kind;
    }

    /**
     * 若归档声明了应用入口类，则返回该类名。
     */
    public String mainClass() {
        return mainClass;
    }

    /**
     * 指示归档中是否存在带版本的 XJar Plus 密钥信封和索引。
     */
    public boolean encrypted() {
        return encrypted;
    }

    /**
     * 返回校验后的条目数量；若源归档包含目录，也计入数量。
     */
    public int entries() {
        return entries;
    }

    /**
     * 返回推导出的最低 Java 版本；加密归档须经 verify 认证字节码后才能确定，否则返回 null。
     */
    public Integer minimumJava() {
        return minimumJava;
    }
}
