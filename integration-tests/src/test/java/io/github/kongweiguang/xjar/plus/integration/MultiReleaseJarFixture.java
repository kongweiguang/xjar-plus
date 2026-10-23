package io.github.kongweiguang.xjar.plus.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * 构造真实的多版本可执行 JAR，由子 JVM 端到端验证按版本选择类的行为。
 *
 * @author kongweiguang
 */
final class MultiReleaseJarFixture {
    private static final String CLASS_PATH = "fixture/plain/MultiReleaseProbe.class";

    /**
     * 多版本测试样本仅通过静态辅助方法生成，无需创建工具实例。
     */
    private MultiReleaseJarFixture() {
    }

    /**
     * 为已构建的 Java 8 样本添加 Java 9 和 17 实现，同时保留原有启动清单。
     */
    static Path create(Path baseJar, Path work) throws IOException {
        Path sourceRoot = Files.createDirectories(work.resolve("mr-src"));
        Path version9 = compile(sourceRoot.resolve("v9"), "9", "version-9");
        Path version17 = compile(sourceRoot.resolve("v17"), "17", "version-17");
        Path multiRelease = work.resolve("plain-multi-release.jar");

        try (JarFile source = new JarFile(baseJar.toFile())) {
            Manifest manifest = new Manifest(source.getManifest());
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().putValue("Multi-Release", "true");
            try (OutputStream output = Files.newOutputStream(multiRelease);
                 JarOutputStream target = new JarOutputStream(output, manifest)) {
                source.stream().filter(entry -> !entry.isDirectory())
                        .filter(entry -> !JarFile.MANIFEST_NAME.equalsIgnoreCase(entry.getName()))
                        .forEach(entry -> copyEntry(source, entry, target));
                addVariant(target, version9, "META-INF/versions/9/" + CLASS_PATH);
                addVariant(target, version17, "META-INF/versions/17/" + CLASS_PATH);
            }
        }
        return multiRelease;
    }

    /**
     * 按声明的 Java 版本编译各实现，避免较新版本的类意外在 Java 8 上加载。
     */
    private static Path compile(Path root, String release, String marker) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("The integration harness must run on a JDK with javac, not a JRE.");
        }
        Path source = Files.createDirectories(root.resolve("src/fixture/plain"))
                .resolve("MultiReleaseProbe.java");
        Path output = Files.createDirectories(root.resolve("classes"));
        String java = "package fixture.plain; public final class MultiReleaseProbe { "
                      + "public static String version() { return \"" + marker + "\"; } }";
        Files.writeString(source, java);
        int result = compiler.run(null, null, null, "--release", release, "-d", output.toString(), source.toString());
        if (result != 0) {
            throw new IllegalStateException("Could not compile multi-release fixture for Java " + release);
        }
        return output.resolve(CLASS_PATH);
    }

    /**
     * 复制原始条目时不保留压缩长度，避免旧元数据与新归档流不一致。
     */
    private static void copyEntry(JarFile source, JarEntry entry, JarOutputStream target) {
        try (InputStream input = source.getInputStream(entry)) {
            JarEntry copy = new JarEntry(entry.getName());
            copy.setTime(entry.getTime());
            target.putNextEntry(copy);
            input.transferTo(target);
            target.closeEntry();
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
    }

    /**
     * 将已编译的类放入 JAR 的版本目录，同时保持应用可见的类名一致。
     */
    private static void addVariant(JarOutputStream target, Path classFile, String path) throws IOException {
        target.putNextEntry(new JarEntry(path));
        try (InputStream input = Files.newInputStream(classFile)) {
            input.transferTo(target);
        }
        target.closeEntry();
    }
}
