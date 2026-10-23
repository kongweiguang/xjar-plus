/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

/**
 * 受支持的可执行归档布局；检查时拒绝未知自定义启动器，不进行猜测。
 */
enum ArchiveFamily {
    PLAIN("PLAIN", "io.github.kongweiguang.xjar.plus.runtime.XJarPlusLauncher", 8),
    BOOT_CLASSIC("BOOT_CLASSIC", "io.github.kongweiguang.xjar.plus.runtime.boot.XJarPlusBootLauncher", 8),
    BOOT_MODERN("BOOT_MODERN", "io.github.kongweiguang.xjar.plus.runtime.boot.modern.XJarPlusBootLauncher", 17),
    SOLON("SOLON", "io.github.kongweiguang.xjar.plus.runtime.solon.XJarPlusSolonLauncher", 8);

    private final String displayName;
    private final String runtimeMainClass;
    private final int minimumJava;

    /**
 * 在同一张表中维护归档识别名称和注入的运行时入口类。
     */
    ArchiveFamily(String displayName, String runtimeMainClass, int minimumJava) {
        this.displayName = displayName;
        this.runtimeMainClass = runtimeMainClass;
        this.minimumJava = minimumJava;
    }

    /**
 * 返回稳定的公开家族名称。
     */
    String displayName() {
        return displayName;
    }

    /**
 * 返回该归档家族唯一受支持的运行时入口类。
     */
    String runtimeMainClass() {
        return runtimeMainClass;
    }

    /**
 * 返回该家族加载器带来的最低运行时版本要求。
     */
    int minimumJava() {
        return minimumJava;
    }
}
