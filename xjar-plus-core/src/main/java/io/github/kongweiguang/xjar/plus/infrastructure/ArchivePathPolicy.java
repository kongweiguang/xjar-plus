/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 集中校验路径身份，避免外层归档、嵌套归档和签名策略各自出现差异。
 */
final class ArchivePathPolicy {
    private static final String RUNTIME_PREFIX = "io/github/kongweiguang/xjar/plus/runtime/";
    private static final String FORMAT_PREFIX = "io/github/kongweiguang/xjar/plus/format/";

    /**
 * 禁止创建共享可变策略实例；所有校验只依赖传入的归档路径。
     */
    private ArchivePathPolicy() {
    }

    /**
 * 在路径用作 ZIP 查找名或认证索引键前拒绝别名和目录穿越。
     */
    static void validate(String name, boolean directory) throws IOException {
        final String candidate = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (candidate.isEmpty() || candidate.startsWith("/") || candidate.contains("\\")
            || candidate.contains("\0") || candidate.contains("//") || candidate.contains("!/")) {
            throw new IOException("Archive entry path is unsafe or ambiguous: " + name);
        }
        validateSegments(candidate, name);
    }

    /**
 * 按斜杠分隔的单个路径段校验，使目录穿越检测不依赖平台路径规则。
     */
    private static void validateSegments(String candidate, String originalName) throws IOException {
        for (String segment : candidate.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IOException("Archive entry path contains an unsafe segment: " + originalName);
            }
        }
    }

    /**
 * 防止输入归档覆盖失败即关闭加载所需的元数据和引导命名空间。
     */
    static void rejectReservedNames(Set<String> names) throws IOException {
        for (String name : names) {
            if (name.startsWith(ArchiveSupport.META_ROOT) || name.startsWith(RUNTIME_PREFIX)
                || name.startsWith(FORMAT_PREFIX)) {
                throw new IOException("Input archive uses a reserved XJar Plus path: " + name);
            }
        }
    }

    /**
 * 根据元数据位置识别顶层 JAR 签名记录，而非仅检查路径中是否出现特定后缀。
     */
    static boolean isSignatureMetadata(String name) {
        final String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        final String leaf = upper.substring("META-INF/".length());
        return !leaf.contains("/") && (leaf.endsWith(".SF") || leaf.endsWith(".RSA")
                                       || leaf.endsWith(".DSA") || leaf.endsWith(".EC") || leaf.startsWith("SIG-"));
    }

    /**
 * 谨慎拒绝类似签名元数据的路径，因为转换会使签名摘要失效。
     */
    static boolean isSignedMetadata(String name) {
        final String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-"));
    }

    /**
 * 仅允许在嵌套条目映射已定义且可认证的加载器布局中使用逻辑子归档路径。
     */
    static boolean isNestedJar(String name, ArchiveFamily family) {
        if (!name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return false;
        }
        return name.startsWith("BOOT-INF/lib/") || name.startsWith("WEB-INF/lib/")
               || family == ArchiveFamily.SOLON && (name.startsWith("lib/") || name.startsWith("libs/"));
    }
}
