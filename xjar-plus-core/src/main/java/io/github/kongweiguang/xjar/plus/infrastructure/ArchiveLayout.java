/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import java.io.IOException;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.Manifest;

/**
 * 识别受支持的启动布局，并校验受保护归档必需的控制元数据。
 */
final class ArchiveLayout {
    private static final String BOOT_CLASSIC_MAIN = "org.springframework.boot.loader.JarLauncher";
    private static final String BOOT_LAUNCHER_SHIM_MAIN = "org.springframework.boot.loader.launch.JarLauncher";
    private static final String BOOT_CLASSIC_LAUNCHER = "org/springframework/boot/loader/JarLauncher.class";
    private static final String BOOT_CLASSIC_CLASS_LOADER =
            "org/springframework/boot/loader/LaunchedURLClassLoader.class";
    private static final String BOOT_CLASSIC_ARCHIVE = "org/springframework/boot/loader/archive/JarFileArchive.class";
    private static final String BOOT_MODERN_LAUNCHER = "org/springframework/boot/loader/launch/JarLauncher.class";
    private static final String BOOT_MODERN_CLASS_LOADER =
            "org/springframework/boot/loader/launch/LaunchedClassLoader.class";
    private static final String BOOT_MODERN_ARCHIVE =
            "org/springframework/boot/loader/launch/JarFileArchive.class";
    private static final String BOOT_CLASSES_PREFIX = "BOOT-INF/classes/";
    private static final String BOOT_LIB_PREFIX = "BOOT-INF/lib/";

    /**
 * 保持布局识别无状态，使每次判断都基于调用方当前的归档清单。
     */
    private ArchiveLayout() {
    }

    /**
 * 识别不完整的 XJar Plus 标记，防止损坏的受保护归档回退为普通 JAR 处理。
     */
    static boolean encryptedMetadata(Map<String, JarEntry> entries, Manifest manifest) throws IOException {
        final boolean hasEnvelope = entries.containsKey(ArchiveSupport.ENVELOPE);
        final boolean hasIndex = entries.containsKey(ArchiveSupport.INDEX);
        final boolean hasOriginalManifest = entries.containsKey(ArchiveSupport.ORIGINAL_MANIFEST);
        final boolean marker = manifest != null && "2".equals(
                manifest.getMainAttributes().getValue("XJar-Plus-Version"));
        if (!hasEnvelope && !hasIndex && !hasOriginalManifest && !marker) {
            return false;
        }
        if (!(hasEnvelope && hasIndex && hasOriginalManifest && marker)) {
            throw new IOException("Archive contains incomplete XJar Plus v2 metadata");
        }
        return true;
    }

    /**
 * 只有所有 v2 控制记录和已知运行时入口均存在时，才判定归档已受保护。
     */
    static void validateEncryptedEntries(Map<String, JarEntry> entries, Manifest manifest) throws IOException {
        if (manifest == null || entries.get(ArchiveSupport.ENVELOPE).isDirectory()
            || entries.get(ArchiveSupport.INDEX).isDirectory()
            || entries.get(ArchiveSupport.ORIGINAL_MANIFEST).isDirectory()) {
            throw new IOException("Encrypted archive metadata entries are malformed");
        }
        final String launcher = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        if (runtimeFamily(launcher) == null) {
            throw new IOException("Encrypted archive has an unknown XJar Plus runtime launcher");
        }
    }

    /**
 * 仅通过有限的家族表解析受保护启动器；遇到未知值时失败关闭。
     */
    static ArchiveFamily encryptedFamily(Manifest manifest) throws IOException {
        if (manifest == null) {
            throw new IOException("Encrypted archive manifest is missing");
        }
        final ArchiveFamily family = runtimeFamily(
                manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
        if (family == null) {
            throw new IOException("Encrypted archive runtime launcher is unsupported");
        }
        return family;
    }

    /**
 * 根据声明的入口和 Boot 布局判断，避免把仅包含加载器依赖的普通 uber JAR 误判为 Boot 应用。
     */
    static ArchiveFamily plainFamily(Manifest manifest, Map<String, JarEntry> entries) throws IOException {
        if (manifest == null) {
            return ArchiveFamily.PLAIN;
        }
        final String main = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
        final boolean bootClassic = hasBootClassicStructure(entries);
        final boolean bootModern = hasBootModernStructure(entries);
        if (hasMixedBootLoaderStructures(main, entries, bootClassic, bootModern)) {
            throw new IOException("Archive contains mixed Spring Boot classic and modern loader structures");
        }
        final ArchiveFamily bootFamily = supportedBootFamily(main, entries, bootClassic, bootModern);
        if (bootFamily != null) {
            return bootFamily;
        }
        rejectIncompleteBootApplication(main, entries);
        if ("org.noear.solon.loader.JarLauncher".equals(main)) {
            return ArchiveFamily.SOLON;
        }
        rejectUnknownFrameworkLauncher(main);
        return ArchiveFamily.PLAIN;
    }

    /**
 * 只有入口或应用布局表明加载器属于可执行结构时，才拒绝混合的原生加载器。
     */
    private static boolean hasMixedBootLoaderStructures(String main, Map<String, JarEntry> entries,
                                                        boolean bootClassic, boolean bootModern) {
        final boolean mixed = (bootClassic && hasBootModernLoaderMarker(entries))
                              || (bootModern && hasBootClassicLoaderMarker(entries));
        return mixed && (hasBootApplicationLayout(entries) || isBootLauncherMain(main));
    }

    /**
 * 只接受结构完整且 manifest 启动器与之匹配的标准归档家族布局。
     */
    private static ArchiveFamily supportedBootFamily(String main, Map<String, JarEntry> entries,
                                                     boolean bootClassic, boolean bootModern) {
        if (bootClassic && (BOOT_CLASSIC_MAIN.equals(main)
                            || (BOOT_LAUNCHER_SHIM_MAIN.equals(main) && entries.containsKey(BOOT_MODERN_LAUNCHER)))) {
            return ArchiveFamily.BOOT_CLASSIC;
        }
        if (bootModern && BOOT_LAUNCHER_SHIM_MAIN.equals(main)) {
            return ArchiveFamily.BOOT_MODERN;
        }
        return null;
    }

    /**
 * 对 Boot 启动器和加载器支撑的应用布局失败关闭；单独出现 BOOT-INF 数据路径不足以触发拒绝。
     */
    private static void rejectIncompleteBootApplication(String main, Map<String, JarEntry> entries) throws IOException {
        if (isBootLauncherMain(main) || hasBootApplicationWithLoader(entries)) {
            throw new IOException("Archive contains an incomplete or unsupported Spring Boot application layout");
        }
    }

    /**
 * 需同时有框架加载器证据和 BOOT-INF 路径，才能将可执行归档与普通数据区分开。
     */
    private static boolean hasBootApplicationWithLoader(Map<String, JarEntry> entries) {
        return hasBootApplicationLayout(entries)
               && (hasBootClassicLoaderMarker(entries) || hasBootModernLoaderMarker(entries));
    }

    /**
 * 区分可执行 fat JAR 布局与作为普通依赖打包的无关加载器类。
     */
    private static boolean hasBootApplicationLayout(Map<String, JarEntry> entries) {
        for (String path : entries.keySet()) {
            if (path.equals("BOOT-INF/classes") || path.startsWith(BOOT_CLASSES_PREFIX)
                || path.equals("BOOT-INF/lib") || path.startsWith(BOOT_LIB_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    /**
 * 仅将 Boot 支持的两个标准启动器入口视为加载器标记确属可执行结构的证据。
     */
    private static boolean isBootLauncherMain(String main) {
        return BOOT_CLASSIC_MAIN.equals(main) || BOOT_LAUNCHER_SHIM_MAIN.equals(main);
    }

    /**
 * 根据实际启动的类加载器和归档实现识别 Boot classic，而非只依据 manifest 中的入口名称。
     */
    private static boolean hasBootClassicStructure(Map<String, JarEntry> entries) {
        return entries.containsKey(BOOT_CLASSIC_LAUNCHER)
               && entries.containsKey(BOOT_CLASSIC_CLASS_LOADER)
               && entries.containsKey(BOOT_CLASSIC_ARCHIVE);
    }

    /**
 * Boot 加载器重写后，要求现代启动器、运行时类加载器及归档类同时存在。
     */
    private static boolean hasBootModernStructure(Map<String, JarEntry> entries) {
        return entries.containsKey(BOOT_MODERN_LAUNCHER)
               && entries.containsKey(BOOT_MODERN_CLASS_LOADER)
               && entries.containsKey(BOOT_MODERN_ARCHIVE);
    }

    /**
 * 识别现代布局专属加载器类；这些类不应出现在官方 classic 兼容入口布局中。
     */
    private static boolean hasBootModernLoaderMarker(Map<String, JarEntry> entries) {
        return entries.containsKey(BOOT_MODERN_CLASS_LOADER) || entries.containsKey(BOOT_MODERN_ARCHIVE);
    }

    /**
 * 识别 classic 专属加载器类，防止混合布局仅凭 manifest 被选作 modern。
     */
    private static boolean hasBootClassicLoaderMarker(Map<String, JarEntry> entries) {
        return entries.containsKey(BOOT_CLASSIC_LAUNCHER)
               || entries.containsKey(BOOT_CLASSIC_CLASS_LOADER)
               || entries.containsKey(BOOT_CLASSIC_ARCHIVE);
    }

    /**
 * 拒绝名称相似但不属于已验证标准布局的框架命名空间。
     */
    private static void rejectUnknownFrameworkLauncher(String main) throws IOException {
        if (main != null && (main.startsWith("org.springframework.boot.loader.")
                             || main.startsWith("org.noear.solon.loader."))) {
            throw new IOException("Input uses an unsupported or custom framework launcher: " + main);
        }
    }

    /**
 * 不使用别名或版本推断，将已知运行时类名映射到对应启动家族。
     */
    private static ArchiveFamily runtimeFamily(String launcher) {
        for (ArchiveFamily family : ArchiveFamily.values()) {
            if (family.runtimeMainClass().equals(launcher)) {
                return family;
            }
        }
        return null;
    }

    /**
 * 保留普通 JAR manifest 语义，同时选择框架应用入口类。
     */
    static String applicationMainClass(Manifest manifest, ArchiveFamily family) {
        if (manifest == null) {
            return null;
        }
        final Attributes attributes = manifest.getMainAttributes();
        if (family == ArchiveFamily.BOOT_CLASSIC || family == ArchiveFamily.BOOT_MODERN) {
            return attributes.getValue("Start-Class");
        }
        if (family == ArchiveFamily.SOLON) {
            final String solonMain = attributes.getValue("Solon-Start-Class");
            return solonMain == null ? attributes.getValue("Start-Class") : solonMain;
        }
        return attributes.getValue(Attributes.Name.MAIN_CLASS);
    }
}
