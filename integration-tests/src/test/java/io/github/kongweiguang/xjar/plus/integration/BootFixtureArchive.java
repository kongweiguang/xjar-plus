/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.integration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * 测试时生成真实的 Boot 归档条目，让二进制 HTTP 与多版本用例保持为可审查的源码。
 */
final class BootFixtureArchive {
    private static final String MR_ENTRY = "BOOT-INF/lib/xjp-mr-probe.jar";
    private static final String MR_CLASS = "fixture/mr/BootMultiReleaseProbe.class";

    /**
     * 辅助类只负责生成相互隔离的临时测试样本，无需创建实例。
     */
    private BootFixtureArchive() {
    }

    /**
     * 加入精确长度的类路径资源和真实的嵌套多版本 JAR，同时保留 Boot 要求的 STORED 依赖布局。
     */
    static Path prepare(Path source, Path output) throws Exception {
        Files.createDirectories(output.getParent());
        Map<String, byte[]> resources = resources();
        byte[] nestedMrJar = multiReleaseJar(output.getParent().resolve("mr-probe-build"));
        try (ZipFile input = new ZipFile(source.toFile());
             ZipOutputStream target = new ZipOutputStream(Files.newOutputStream(output))) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String prefix = "BOOT-INF/classes/static/";
                if (entry.getName().startsWith(prefix) && resources.containsKey(entry.getName().substring(prefix.length()))) {
                    continue;
                }
                if (MR_ENTRY.equals(entry.getName())) {
                    throw new IOException("Fixture already contains reserved MR dependency " + MR_ENTRY);
                }
                copyEntry(input, target, entry);
            }
            for (Map.Entry<String, byte[]> resource : resources.entrySet()) {
                writeEntry(target, "BOOT-INF/classes/static/" + resource.getKey(), resource.getValue(), false);
            }
            writeEntry(target, MR_ENTRY, nestedMrJar, true);
        }
        return output;
    }

    /**
     * 生成可重复的字节内容，覆盖既有 HTTP 长度边界，包括空文件和非整块结尾。
     */
    static Map<String, byte[]> resources() {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        resources.put("asset.txt", "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8));
        resources.put("empty.bin", new byte[0]);
        byte[] block = new byte[16];
        for (int index = 0; index < block.length; index++) {
            block[index] = (byte) index;
        }
        resources.put("block16.bin", block);
        byte[] large = new byte[131089];
        for (int index = 0; index < large.length; index++) {
            large[index] = (byte) (index * 31 + (index >>> 7));
        }
        resources.put("large.bin", large);
        return Map.copyOf(resources);
    }

    /**
     * 按各实现声明的 Java 版本编译，确保验证真实的 JVM 多版本选择，而非字符串模拟。
     */
    private static byte[] multiReleaseJar(Path workspace) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("The compatibility harness needs a JDK to compile the nested MR probe");
        }
        Files.createDirectories(workspace);
        byte[] base = compileVariant(compiler, workspace, 8, "base");
        byte[] version9 = compileVariant(compiler, workspace, 9, "version-9");
        byte[] version17 = compileVariant(compiler, workspace, 17, "version-17");
        Path jar = workspace.resolve("boot-mr-probe.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            writeEntry(output, MR_CLASS, base, false);
            writeEntry(output, "META-INF/versions/9/" + MR_CLASS, version9, false);
            writeEntry(output, "META-INF/versions/17/" + MR_CLASS, version17, false);
        }
        return Files.readAllBytes(jar);
    }

    /**
     * 使用测试工具所在 JDK 的编译器，确保各版本类文件符合目标字节码级别，并兼容 Boot 2 的 Java 8 基线。
     */
    private static byte[] compileVariant(JavaCompiler compiler, Path workspace, int release, String value) throws IOException {
        Path source = workspace.resolve("source/fixture/mr/BootMultiReleaseProbe.java");
        Path output = workspace.resolve("classes-" + release);
        Files.createDirectories(source.getParent());
        Files.createDirectories(output);
        String text = "package fixture.mr;\n"
                      + "public final class BootMultiReleaseProbe {\n"
                      + "  public static String value() { return \"" + value + "\"; }\n"
                      + "}\n";
        Files.writeString(source, text, StandardCharsets.UTF_8);
        int result = compiler.run(null, null, null, "-Xlint:-options", "--release", Integer.toString(release),
                "-d", output.toString(), source.toString());
        if (result != 0) {
            throw new IOException("Could not compile MR probe for Java " + release + ": " + source);
        }
        return Files.readAllBytes(output.resolve(MR_CLASS));
    }

    /**
     * 按解压后的内容复制条目，并按 Boot 启动器要求将嵌套依赖保留为 STORED 格式。
     */
    private static void copyEntry(ZipFile input, ZipOutputStream output, ZipEntry original) throws IOException {
        ZipEntry copy = new ZipEntry(original.getName());
        if (original.getTime() >= 0) {
            copy.setTime(original.getTime());
        }
        if (original.getComment() != null) {
            copy.setComment(original.getComment());
        }
        copy.setMethod(original.getMethod());
        if (original.getMethod() == ZipEntry.STORED) {
            copy.setSize(original.getSize());
            copy.setCompressedSize(original.getSize());
            copy.setCrc(original.getCrc());
        }
        output.putNextEntry(copy);
        try (InputStream stream = input.getInputStream(original)) {
            stream.transferTo(output);
        }
        output.closeEntry();
    }

    /**
     * 生成的嵌套归档使用 STORED 格式，普通测试资源仍可使用常规 ZIP 压缩。
     */
    private static void writeEntry(ZipOutputStream output, String name, byte[] bytes, boolean stored) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCompressedSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }
}
