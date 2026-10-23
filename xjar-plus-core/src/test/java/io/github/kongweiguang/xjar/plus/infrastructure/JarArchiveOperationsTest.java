/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import io.github.kongweiguang.xjar.plus.domain.model.ArchiveCommand;
import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过领域端口使用临时可执行 JAR 和嵌套 JAR 夹具验证归档适配器。
 */
final class JarArchiveOperationsTest {
    private static final char[] PASSWORD = "跨语言-🔐-test".toCharArray();

    @TempDir
    private Path temporaryDirectory;

    /**
     * 使命令夹具遵循领域端口的秘密复制和关闭约定。
     */
    private static ArchiveCommand command(Path input, Path output, char[] password) {
        return new ArchiveCommand(input, output, password, java.util.List.of(), java.util.List.of(), false,
                (stage, percent) -> {
                }, CancellationProbe.NEVER);
    }

    /**
     * 创建包含真实可执行 manifest 和认证嵌套依赖的稳定小型夹具。
     */
    private static Map<String, byte[]> executableEntries() throws IOException {
        final Map<String, byte[]> nestedEntries = Map.of(
                "dependency/Library.class", classHeader(8),
                "config/defaults.properties", "enabled=true\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/Main.class", classHeader(8));
        entries.put("config/application.properties", "name=demo\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put("BOOT-INF/lib/dependency.jar", jarBytes(nestedEntries));
        return entries;
    }

    /**
     * 创建不依赖编译器的可执行归档，使密码学测试快速且结果确定。
     */
    private static void writeExecutableJar(Path path, Map<String, byte[]> entries) throws IOException {
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "demo.Main");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (final Map.Entry<String, byte[]> fixture : entries.entrySet()) {
                output.putNextEntry(new JarEntry(fixture.getKey()));
                output.write(fixture.getValue());
                output.closeEntry();
            }
        }
    }

    /**
     * 构造 classic manifest 元数据和完整加载器标记，使夹具符合受支持的归档布局。
     */
    private static void writeBootClassicJar(Path path, Map<String, byte[]> entries) throws IOException {
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "org.springframework.boot.loader.JarLauncher");
        manifest.getMainAttributes().putValue("Start-Class", "demo.Main");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (final Map.Entry<String, byte[]> fixture : entries.entrySet()) {
                output.putNextEntry(new JarEntry(fixture.getKey()));
                output.write(fixture.getValue());
                output.closeEntry();
            }
        }
    }

    /**
     * 序列化嵌套夹具条目，并生成归档读取器可可靠定位的中央目录。
     */
    private static byte[] jarBytes(Map<String, byte[]> entries) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             JarOutputStream output = new JarOutputStream(bytes)) {
            for (final Map.Entry<String, byte[]> fixture : entries.entrySet()) {
                output.putNextEntry(new JarEntry(fixture.getKey()));
                output.write(fixture.getValue());
                output.closeEntry();
            }
            output.finish();
            return bytes.toByteArray();
        }
    }

    /**
     * 构造指定 Java 主版本的 class 文件头，不包含可执行方法体。
     */
    private static byte[] classHeader(int feature) {
        final int major = feature + 44;
        return new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe,
                0, 0, (byte) (major >>> 8), (byte) major};
    }

    /**
     * 读取 ZIP 条目的原始字节，使往返断言可比较条目名称和精确载荷。
     */
    private static Map<String, byte[]> readEntries(Path path) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            final var enumeration = jar.entries();
            while (enumeration.hasMoreElements()) {
                final JarEntry entry = enumeration.nextElement();
                if (!entry.isDirectory()) {
                    try (InputStream input = jar.getInputStream(entry)) {
                        entries.put(entry.getName(), input.readAllBytes());
                    }
                }
            }
        }
        return entries;
    }

    /**
     * 暂存归档发布或恢复前，必须认证所有外层和嵌套记录。
     */
    @Test
    void encryptVerifyAndDecryptRestoreEveryOriginalEntry() throws Exception {
        final Path input = temporaryDirectory.resolve("source.jar");
        final Path encrypted = temporaryDirectory.resolve("protected.jar");
        final Path restored = temporaryDirectory.resolve("restored.jar");
        writeExecutableJar(input, executableEntries());
        final Map<String, byte[]> expected = readEntries(input);
        final JarArchiveOperations operations = new JarArchiveOperations();

        try (ArchiveCommand request = command(input, encrypted, PASSWORD)) {
            operations.encrypt(request);
        }
        assertTrue(Files.isRegularFile(encrypted));
        try (ArchiveCommand request = command(encrypted, null, PASSWORD)) {
            assertTrue(operations.verify(request).encrypted());
        }
        try (ArchiveCommand request = command(encrypted, restored, PASSWORD)) {
            operations.decrypt(request);
        }

        final Map<String, byte[]> actual = readEntries(restored);
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((name, bytes) -> assertArrayEquals(bytes, actual.get(name), name));
    }

    /**
     * 密钥解包失败时不得回退到明文处理，也不得修改受保护源文件。
     */
    @Test
    void verifyRejectsAnIncorrectPasswordAndKeepsTheInput() throws Exception {
        final Path input = temporaryDirectory.resolve("source.jar");
        final Path encrypted = temporaryDirectory.resolve("protected.jar");
        writeExecutableJar(input, executableEntries());
        final JarArchiveOperations operations = new JarArchiveOperations();
        try (ArchiveCommand request = command(input, encrypted, PASSWORD)) {
            operations.encrypt(request);
        }
        final byte[] before = Files.readAllBytes(encrypted);

        assertThrows(IOException.class, () -> {
            try (ArchiveCommand request = command(encrypted, null, "wrong-password".toCharArray())) {
                operations.verify(request);
            }
        });
        assertArrayEquals(before, Files.readAllBytes(encrypted));
    }

    /**
     * 确保验证结果返回认证后的原始元数据中的应用入口，而非运行时包装入口。
     */
    @Test
    void verifyReturnsAuthenticatedApplicationMainClass() throws Exception {
        final Path input = temporaryDirectory.resolve("source.jar");
        final Path encrypted = temporaryDirectory.resolve("protected.jar");
        writeExecutableJar(input, executableEntries());
        final JarArchiveOperations operations = new JarArchiveOperations();

        try (ArchiveCommand request = command(input, encrypted, PASSWORD)) {
            operations.encrypt(request);
        }
        try (ArchiveCommand request = command(encrypted, null, PASSWORD)) {
            assertEquals("demo.Main", operations.verify(request).mainClass());
        }
    }

    /**
     * 已有目标属于用户数据，即使操作被拒绝也必须保持原样。
     */
    @Test
    void encryptRefusesToReplaceAnExistingOutput() throws Exception {
        final Path input = temporaryDirectory.resolve("source.jar");
        final Path output = temporaryDirectory.resolve("existing.jar");
        final byte[] original = "preserve existing output".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeExecutableJar(input, executableEntries());
        Files.write(output, original);

        assertThrows(IOException.class, () -> {
            try (ArchiveCommand request = command(input, output, PASSWORD)) {
                new JarArchiveOperations().encrypt(request);
            }
        });
        assertArrayEquals(original, Files.readAllBytes(output));
    }

    /**
     * 多版本覆盖项和模块描述符不会提高普通类路径的最低 Java 版本。
     */
    @Test
    void inspectExcludesMultiReleaseClassesAndModuleDescriptors() throws Exception {
        final Path input = temporaryDirectory.resolve("versions.jar");
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/Main.class", classHeader(8));
        entries.put("META-INF/versions/21/demo/Main.class", classHeader(21));
        entries.put("module-info.class", classHeader(25));
        entries.put("demo/module-info.class", classHeader(25));
        writeExecutableJar(input, entries);

        assertEquals(8, new JarArchiveOperations().inspect(input).minimumJava());
    }

    /**
     * 嵌套可执行依赖中的基础类会参与发行包 JDK 预检。
     */
    @Test
    void inspectIncludesNestedDependencyBaseClasses() throws Exception {
        final Path input = temporaryDirectory.resolve("nested.jar");
        final Map<String, byte[]> nestedEntries = Map.of(
                "dep/Service.class", classHeader(21),
                "META-INF/versions/25/dep/Service.class", classHeader(25),
                "module-info.class", classHeader(25));
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("demo/Main.class", classHeader(8));
        entries.put("BOOT-INF/lib/dependency.jar", jarBytes(nestedEntries));
        writeExecutableJar(input, entries);

        assertEquals(21, new JarArchiveOperations().inspect(input).minimumJava());
    }

    /**
     * 公开检查无法推导受保护 class 版本；验证可在认证字节后完成推导。
     */
    @Test
    void encryptedPlainArchiveReportsMinimumJavaOnlyAfterVerification() throws Exception {
        final Path input = temporaryDirectory.resolve("java21.jar");
        final Path encrypted = temporaryDirectory.resolve("java21-protected.jar");
        final Map<String, byte[]> entries = Map.of("demo/Main.class", classHeader(21));
        writeExecutableJar(input, entries);
        final JarArchiveOperations operations = new JarArchiveOperations();

        assertEquals(21, operations.inspect(input).minimumJava());
        try (ArchiveCommand request = command(input, encrypted, PASSWORD)) {
            operations.encrypt(request);
        }

        assertNull(XJarPlus.inspect(encrypted).minimumJava());
        assertEquals(21, XJarPlus.verify(encrypted, PASSWORD).minimumJava());
    }

    /**
     * 公开验证会扫描已认证的 Boot 依赖文件头，但计算基础版本时忽略 MR 覆盖项。
     */
    @Test
    void encryptedBootArchiveReportsDependencyMinimumJavaOnlyAfterVerification() throws Exception {
        final Path input = temporaryDirectory.resolve("boot-classic.jar");
        final Path encrypted = temporaryDirectory.resolve("boot-classic-protected.jar");
        final Map<String, byte[]> dependency = Map.of(
                "dep/Library.class", classHeader(21),
                "META-INF/versions/25/dep/Library.class", classHeader(25),
                "module-info.class", classHeader(25));
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("org/springframework/boot/loader/JarLauncher.class", classHeader(8));
        entries.put("org/springframework/boot/loader/LaunchedURLClassLoader.class", classHeader(8));
        entries.put("org/springframework/boot/loader/archive/JarFileArchive.class", classHeader(8));
        entries.put("BOOT-INF/classes/demo/Main.class", classHeader(8));
        entries.put("BOOT-INF/lib/dependency.jar", jarBytes(dependency));
        writeBootClassicJar(input, entries);
        final JarArchiveOperations operations = new JarArchiveOperations();

        assertEquals(21, operations.inspect(input).minimumJava());
        try (ArchiveCommand request = command(input, encrypted, PASSWORD)) {
            operations.encrypt(request);
        }

        assertNull(XJarPlus.inspect(encrypted).minimumJava());
        assertEquals(21, XJarPlus.verify(encrypted, PASSWORD).minimumJava());
    }
}
