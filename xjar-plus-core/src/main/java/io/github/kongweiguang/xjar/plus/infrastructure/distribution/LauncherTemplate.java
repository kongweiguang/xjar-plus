/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure.distribution;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * 从 IDE 类路径或经过 shade 的公开 CLI JAR 中读取白名单内的 Go 模板。
 */
public final class LauncherTemplate {
    private static final String PREFIX = "xjar-plus-launcher-template/";
    private static final long MAX_SOURCE_BYTES = 16L * 1024 * 1024;

    /**
     * 将模板读取限制在公开的物化操作中。
     */
    private LauncherTemplate() {
    }

    /**
     * 只复制项目源文件，不复制客户配置、应用程序或 JDK。
     */
    public static void materialize(final Path destination) throws IOException {
        Files.createDirectory(destination);
        final URL resource = LauncherTemplate.class.getClassLoader().getResource(PREFIX + "go.mod");
        if (resource == null) {
            throw new IOException("Launcher template is missing; rebuild the complete XJar Plus tool distribution");
        }
        if ("jar".equals(resource.getProtocol())) {
            fromJar(resource, destination);
        } else if ("file".equals(resource.getProtocol())) {
            fromDirectory(resource, destination);
        } else {
            throw new IOException("Unsupported launcher template URL protocol: " + resource.getProtocol());
        }
        if (!Files.isRegularFile(destination.resolve("cmd/launcher/main.go"))) {
            throw new IOException("Launcher template is incomplete");
        }
    }

    /**
     * 枚举公共 JAR 时不关闭由类加载器缓存并共享的 JarFile。
     */
    private static void fromJar(final URL resource, final Path destination) throws IOException {
        final JarURLConnection connection = (JarURLConnection) resource.openConnection();
        connection.setUseCaches(false);
        try (JarFile jar = connection.getJarFile()) {
            final var entries = jar.entries();
            long remaining = MAX_SOURCE_BYTES;
            while (entries.hasMoreElements()) {
                final var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(PREFIX)) {
                    continue;
                }
                final String name = entry.getName().substring(PREFIX.length());
                if (allowed(name)) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        remaining -= copy(input, destination.resolve(name), remaining);
                    }
                }
            }
        }
    }

    /**
     * 同时支持 Maven 测试和 IDE 类路径，不依赖开发者当前仓库工作目录。
     */
    private static void fromDirectory(final URL resource, final Path destination) throws IOException {
        final Path root;
        try {
            root = Path.of(resource.toURI()).getParent();
        } catch (URISyntaxException failure) {
            throw new IOException("Invalid launcher template resource path", failure);
        }
        try (var files = Files.walk(root)) {
            long remaining = MAX_SOURCE_BYTES;
            final var iterator = files.iterator();
            while (iterator.hasNext()) {
                final Path source = iterator.next();
                final String name = root.relativize(source).toString().replace('\\', '/');
                if (Files.isRegularFile(source) && !Files.isSymbolicLink(source) && allowed(name)) {
                    try (InputStream input = Files.newInputStream(source)) {
                        remaining -= copy(input, destination.resolve(name), remaining);
                    }
                }
            }
        }
    }

    /**
     * 维护明确的源文件白名单，避免未来新增载荷文件时意外进入公共工具包。
     */
    private static boolean allowed(final String name) {
        if (name.contains("..") || name.contains(":") || name.contains("\\") || name.startsWith("/")) {
            return false;
        }
        return "go.mod".equals(name) || "go.sum".equals(name)
               || "cmd/launcher/payload/README.md".equals(name)
               || ((name.startsWith("cmd/launcher/") || name.startsWith("internal/"))
                   && name.endsWith(".go") && !name.endsWith("_test.go"));
    }

    /**
     * 对所有源文件的累计复制量设置上限，避免替换后的工具归档耗尽暂存卷空间。
     */
    private static long copy(final InputStream input, final Path destination, final long remaining) throws IOException {
        Files.createDirectories(destination.getParent());
        try (var output = Files.newOutputStream(destination, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            final byte[] buffer = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > remaining) {
                    throw new IOException("Launcher source template exceeds its size limit");
                }
                output.write(buffer, 0, count);
            }
            return total;
        }
    }
}
