/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 使用真实 JAR manifest 和加载器条目布局验证受支持的 Boot 家族识别。
 */
final class ArchiveLayoutTest {
    private static final String CLASSIC_MAIN = "org.springframework.boot.loader.JarLauncher";
    private static final String LAUNCHER_SHIM_MAIN = "org.springframework.boot.loader.launch.JarLauncher";
    private static final String CLASSIC_LAUNCHER = "org/springframework/boot/loader/JarLauncher.class";
    private static final String CLASSIC_CLASS_LOADER = "org/springframework/boot/loader/LaunchedURLClassLoader.class";
    private static final String CLASSIC_ARCHIVE = "org/springframework/boot/loader/archive/JarFileArchive.class";
    private static final String MODERN_LAUNCHER = "org/springframework/boot/loader/launch/JarLauncher.class";
    private static final String MODERN_CLASS_LOADER =
            "org/springframework/boot/loader/launch/LaunchedClassLoader.class";
    private static final String MODERN_ARCHIVE = "org/springframework/boot/loader/launch/JarFileArchive.class";

    @TempDir
    private Path temporaryDirectory;

    /**
     * 创建仅包含家族识别所需加载器结构条目的可执行归档。
     */
    private static void writeArchive(final Path output, final String mainClass, final String... entryNames)
            throws IOException {
        final Manifest manifest = new Manifest();
        final Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output), manifest)) {
            for (final String entryName : entryNames) {
                jar.putNextEntry(new JarEntry(entryName));
                jar.closeEntry();
            }
        }
    }

    /**
     * 使用已确认的 classic 加载器结构识别 Boot 3.2 采用 modern 包名的兼容入口。
     */
    @Test
    void boot32ClassicShimUsesClassicLayout() throws Exception {
        assertEquals(ArchiveFamily.BOOT_CLASSIC, inspect("boot32-classic-shim.jar", LAUNCHER_SHIM_MAIN,
                CLASSIC_LAUNCHER, CLASSIC_CLASS_LOADER, CLASSIC_ARCHIVE, MODERN_LAUNCHER));
    }

    /**
     * 只有 classic 所需加载器类全部存在时才接受旧式 manifest 入口。
     */
    @Test
    void bootClassicManifestUsesClassicLayout() throws Exception {
        assertEquals(ArchiveFamily.BOOT_CLASSIC, inspect("boot-classic.jar", CLASSIC_MAIN,
                CLASSIC_LAUNCHER, CLASSIC_CLASS_LOADER, CLASSIC_ARCHIVE));
    }

    /**
     * 要求同时存在 modern 启动类加载器和归档实现，确保兼容入口不会被误判为 modern。
     */
    @Test
    void modernBootLayoutRemainsModern() throws Exception {
        assertEquals(ArchiveFamily.BOOT_MODERN, inspect("boot-modern.jar", LAUNCHER_SHIM_MAIN,
                MODERN_LAUNCHER, MODERN_CLASS_LOADER, MODERN_ARCHIVE));
    }

    /**
     * 同时出现两种加载器家族时拒绝归档，因为 manifest 无法安全确定目标适配器。
     */
    @Test
    void mixedBootLayoutsFailClosed() {
        assertThrows(IOException.class, () -> inspect("boot-mixed.jar", LAUNCHER_SHIM_MAIN,
                CLASSIC_LAUNCHER, CLASSIC_CLASS_LOADER, CLASSIC_ARCHIVE,
                MODERN_LAUNCHER, MODERN_CLASS_LOADER, MODERN_ARCHIVE));
    }

    /**
     * 即使加载器类看似标准，也不接受实际 Boot 嵌套应用布局中的自定义启动器。
     */
    @Test
    void customMainClassWithClassicLoaderStructureIsRejected() {
        assertThrows(IOException.class, () -> inspect("boot-custom-main.jar", "example.CustomLauncher",
                CLASSIC_LAUNCHER, CLASSIC_CLASS_LOADER, CLASSIC_ARCHIVE, "BOOT-INF/classes/demo/Main.class"));
    }

    /**
     * 普通 uber JAR 即使打包了完整 Boot 加载器类，也仍按普通归档处理。
     */
    @Test
    void ordinaryUberJarWithBundledBootLoadersRemainsPlain() throws Exception {
        assertEquals(ArchiveFamily.PLAIN,
                inspect("plain-uber.jar", "example.Main", "example/Main.class",
                        CLASSIC_LAUNCHER, CLASSIC_CLASS_LOADER, CLASSIC_ARCHIVE,
                        MODERN_LAUNCHER, MODERN_CLASS_LOADER, MODERN_ARCHIVE));
    }

    /**
     * 用户数据和嵌套依赖仅使用类似 Boot 的路径时，不足以推断该归档使用 Boot 启动器。
     */
    @Test
    void ordinaryJarWithBootInfPathsRemainsPlain() throws Exception {
        assertEquals(ArchiveFamily.PLAIN,
                inspect("plain-boot-inf-data.jar", "example.Main",
                        "BOOT-INF/classes/config/application.yml", "BOOT-INF/lib/dependency.jar"));
    }

    /**
     * 已知 Boot 启动器缺少完整原生加载器结构时直接拒绝，不猜测归档家族。
     */
    @Test
    void incompleteStandardBootLauncherFailsClosed() {
        assertThrows(IOException.class, () -> inspect("boot-incomplete.jar", CLASSIC_MAIN, CLASSIC_LAUNCHER));
    }

    /**
     * 写入小型磁盘 JAR，因为布局判断必须同时依据实际 manifest 和条目名称。
     */
    private ArchiveFamily inspect(final String name, final String mainClass, final String... entryNames)
            throws IOException {
        final Path archive = temporaryDirectory.resolve(name);
        writeArchive(archive, mainClass, entryNames);
        try (JarFile jar = new JarFile(archive.toFile(), false)) {
            final Map<String, JarEntry> entries = new HashMap<>();
            final Enumeration<JarEntry> enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                final JarEntry entry = enumeration.nextElement();
                entries.put(entry.getName(), entry);
            }
            return ArchiveLayout.plainFamily(jar.getManifest(), entries);
        }
    }
}
