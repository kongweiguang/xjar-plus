/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.domain.model;

/**
 * 领域层归档事实，不依赖 CLI 和 UI 的展示类型。
 */
public final class ArchiveSummary {
    private final String kind;
    private final String mainClass;
    private final boolean encrypted;
    private final int entries;
    private final Integer minimumJava;

    /**
     * 保存检查、验证和加密用例共用的已校验归档事实。
     */
    public ArchiveSummary(String kind, String mainClass, boolean encrypted, int entries, Integer minimumJava) {
        this.kind = kind;
        this.mainClass = mainClass;
        this.encrypted = encrypted;
        this.entries = entries;
        this.minimumJava = minimumJava;
    }

    /**
     * 返回规范的归档家族标识。
     */
    public String kind() {
        return kind;
    }

    /**
     * 若存在应用入口类，则返回其名称。
     */
    public String mainClass() {
        return mainClass;
    }

    /**
     * 返回是否存在 v2 保护元数据。
     */
    public boolean encrypted() {
        return encrypted;
    }

    /**
     * 返回已校验的 ZIP 条目数量。
     */
    public int entries() {
        return entries;
    }

    /**
     * 返回已知最低 Java 版本；加密字节码尚未认证时返回 null。
     */
    public Integer minimumJava() {
        return minimumJava;
    }
}
