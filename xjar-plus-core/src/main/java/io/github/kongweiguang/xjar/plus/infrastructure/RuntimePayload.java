/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.format.EntryMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * 仅选择并注入一个归档家族所需的引导程序包。
 */
final class RuntimePayload {
    private static final String ROOT = "io/github/kongweiguang/xjar/plus/";
    private static final String RUNTIME = ROOT + "runtime/";
    private static final String FORMAT = ROOT + "format/";

    /**
     * 每次选择结果均由输入确定，因此禁止误创建工具类实例。
     */
    private RuntimePayload() {
    }

    /**
     * 复制共用运行时、格式类和所选家族类，使受保护 JAR 可独立运行。
     */
    static void inject(ArchiveFamily family, JarOutputStream output, List<EntryMetadata> index) throws IOException {
        final ClassLoader loader = RuntimePayload.class.getClassLoader();
        final java.util.Set<String> paths = new java.util.TreeSet<>();
        collect("io/github/kongweiguang/xjar/plus/runtime/common/ArchiveRuntime.class", loader, paths, family);
        collect("io/github/kongweiguang/xjar/plus/format/Crypto.class", loader, paths, family);
        collect(classPath(family.runtimeMainClass()), loader, paths, family);
        if (paths.isEmpty()) {
            throw new IOException("No runtime classes were found for archive family " + family.displayName());
        }
        for (String path : paths) {
            try (InputStream input = loader.getResourceAsStream(path)) {
                if (input == null) {
                    throw new IOException("Runtime payload resource is missing: " + path);
                }
                final JarEntry entry = new JarEntry(path);
                entry.setTime(0L);
                output.putNextEntry(entry);
                final EntryFacts facts = ArchiveSupport.measure(input, output);
                output.closeEntry();
                index.add(ArchiveSupport.metadata(path, false, facts));
            }
        }
    }

    /**
     * 通过资源定位字节码，避免构建期链接可选框架父类依赖。
     */
    private static void collect(String markerPath, ClassLoader loader, java.util.Set<String> paths,
                                ArchiveFamily family) throws IOException {
        final URL resource = loader.getResource(markerPath);
        if (resource == null) {
            throw new IOException("Required runtime adapter resource is missing: " + markerPath);
        }
        final Path location;
        try {
            location = resourceLocation(resource, markerPath);
        } catch (URISyntaxException | IllegalArgumentException failure) {
            throw new IOException("Runtime class location is invalid: " + markerPath, failure);
        }
        if (Files.isDirectory(location)) {
            collectDirectory(location, paths, family);
        } else {
            collectJar(location, paths, family);
        }
    }

    /**
     * 将 class 资源 URL 转换为其所属 classes 目录或模块 JAR，不定义该类。
     */
    private static Path resourceLocation(URL resource, String classPath) throws IOException, URISyntaxException {
        if ("jar".equals(resource.getProtocol())) {
            return Path.of(((JarURLConnection) resource.openConnection()).getJarFileURL().toURI());
        }
        if (!"file".equals(resource.getProtocol())) {
            throw new IOException("Unsupported runtime resource protocol: " + resource.getProtocol());
        }
        final Path classFile = Path.of(resource.toURI());
        Path root = classFile;
        for (String ignored : classPath.split("/")) {
            root = root.getParent();
            if (root == null) {
                throw new IOException("Runtime resource path has no containing classes directory: " + classPath);
            }
        }
        return root;
    }

    /**
     * 将完整运行时类名转换为类加载器使用的精确资源键。
     */
    private static String classPath(String className) {
        return className.replace('.', '/') + ".class";
    }

    /**
     * 从 Maven classes 目录枚举 class 文件，不纳入无关资源。
     */
    private static void collectDirectory(Path root, java.util.Set<String> paths, ArchiveFamily family)
            throws IOException {
        final Path packageRoot = root.resolve("io/github/kongweiguang/xjar/plus");
        if (!Files.isDirectory(packageRoot)) {
            return;
        }
        try (Stream<Path> files = Files.walk(packageRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                final String name = root.relativize(file).toString().replace((char) 92, '/');
                if (name.endsWith(".class") && selected(name, family)) {
                    paths.add(name);
                }
            }
        }
    }

    /**
     * 只从指定依赖 JAR 枚举 class 文件，避免将无关模块放入载荷。
     */
    private static void collectJar(Path jarPath, java.util.Set<String> paths, ArchiveFamily family)
            throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            final var entries = jar.entries();
            while (entries.hasMoreElements()) {
                final JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().endsWith(".class")
                    && selected(entry.getName(), family)) {
                    paths.add(entry.getName());
                }
            }
        }
    }

    /**
     * 格式类和通用类供所有归档使用，框架专属适配器则按归档家族隔离。
     */
    private static boolean selected(String path, ArchiveFamily family) {
        if (path.startsWith(FORMAT) || path.startsWith(RUNTIME + "common/")) {
            return true;
        }
        return switch (family) {
            case PLAIN -> path.startsWith(RUNTIME + "XJarPlusLauncher");
            case BOOT_CLASSIC -> path.startsWith(RUNTIME + "boot/")
                                 && !path.startsWith(RUNTIME + "boot/modern/");
            case BOOT_MODERN -> path.startsWith(RUNTIME + "boot/modern/");
            case SOLON -> path.startsWith(RUNTIME + "solon/");
        };
    }
}
