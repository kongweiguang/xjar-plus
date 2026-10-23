/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure.distribution;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用微型合成可执行文件头覆盖归档校验逻辑，无需携带完整 JDK。
 */
final class JdkArchiveValidatorTest {
    @TempDir
    private Path temporary;
    private int archiveNumber;

    /**
     * 构造符合预期的归档目录树，可选添加外层包装目录和 macOS bundle 布局。
     */
    private static List<TestEntry> runtimeEntries(PlatformFixture platform, String version,
                                                  String wrapper, boolean macBundle) {
        final String base = macBundle ? "Contents/Home/" : "";
        final String prefix = wrapper.isEmpty() ? "" : wrapper + "/";
        final String javaName = platform.target().startsWith("windows-") ? "java.exe" : "java";
        return new ArrayList<>(List.of(
                new TestEntry(prefix + base + "release", release(platform, version), 0100644),
                new TestEntry(prefix + base + "bin/" + javaName, executableHeader(platform), 0100755)));
    }

    /**
     * 使用生产校验器必须规范化的厂商写法生成 release 元数据。
     */
    private static byte[] release(PlatformFixture platform, String version) {
        return ("OS_NAME=\"" + platform.osName() + "\"\nOS_ARCH=\"" + platform.architecture()
                + "\"\nJAVA_VERSION=\"" + version + "\"\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 为目标文件格式和 machine 标识生成仅含文件头的微型可执行文件。
     */
    private static byte[] executableHeader(PlatformFixture platform) {
        if (platform.target().startsWith("windows-")) {
            return peHeader(platform.target().endsWith("amd64") ? "amd64" : "arm64");
        }
        if (platform.target().startsWith("linux-")) {
            return elfHeader(platform.target().endsWith("amd64") ? "amd64" : "arm64");
        }
        return machOHeader(platform.target().endsWith("amd64") ? "amd64" : "arm64");
    }

    /**
     * 不依赖本机工具链，构造最小 PE DOS stub 和 COFF machine 字段。
     */
    private static byte[] peHeader(String architecture) {
        final byte[] bytes = new byte[128];
        bytes[0] = 'M';
        bytes[1] = 'Z';
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(0x3c, 64);
        bytes[64] = 'P';
        bytes[65] = 'E';
        final int machine = architecture.equals("amd64") ? 0x8664 : 0xaa64;
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(68, (short) machine);
        return bytes;
    }

    /**
     * 构造最小的 64 位小端 ELF 标识和 machine 字段。
     */
    private static byte[] elfHeader(String architecture) {
        final byte[] bytes = new byte[64];
        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 2;
        bytes[5] = 1;
        final int machine = architecture.equals("amd64") ? 62 : 183;
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(18, (short) machine);
        return bytes;
    }

    /**
     * 构造符合预期 CPU 类型的最小大端单架构 Mach-O 文件头。
     */
    private static byte[] machOHeader(String architecture) {
        final byte[] bytes = new byte[32];
        final int cpu = architecture.equals("amd64") ? 0x01000007 : 0x0100000c;
        ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(0, 0xfeedfacf).putInt(4, cpu);
        return bytes;
    }

    /**
     * 构造双切片通用 Mach-O 文件头，覆盖 Apple Silicon 厂商归档布局。
     */
    private static byte[] universalMachOHeader(boolean includeArm64) {
        final byte[] bytes = new byte[48];
        final ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        header.putInt(0, 0xcafebabe);
        header.putInt(4, 2);
        header.putInt(8, 0x01000007);
        header.putInt(28, includeArm64 ? 0x0100000c : 0x01000007);
        return bytes;
    }

    /**
     * 替换一条合成文件条目，同时保留夹具中其余归档节点。
     */
    private static void replace(List<TestEntry> entries, String path, byte[] bytes, int mode) {
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).path().equals(path)) {
                entries.set(index, new TestEntry(path, bytes, mode));
                return;
            }
        }
        throw new IllegalArgumentException("fixture entry does not exist: " + path);
    }

    /**
     * 所有声明的目标平台都应接受匹配的元数据和二进制 machine 标识。
     */
    @Test
    void acceptsSupportedPlatformsAndVendorLayouts() throws Exception {
        final List<PlatformFixture> platforms = List.of(
                new PlatformFixture("windows-amd64", "Windows", "x86_64"),
                new PlatformFixture("windows-arm64", "Windows", "aarch64"),
                new PlatformFixture("linux-amd64", "Linux", "x86_64"),
                new PlatformFixture("linux-arm64", "Linux", "aarch64"),
                new PlatformFixture("darwin-amd64", "Mac OS X", "x86_64"),
                new PlatformFixture("darwin-arm64", "Darwin", "aarch64"));
        for (int index = 0; index < platforms.size(); index++) {
            final PlatformFixture platform = platforms.get(index);
            final String root = index == 0 ? "vendor-jdk" : "";
            final boolean macBundle = index == platforms.size() - 1;
            final Path zip = writeArchive(runtimeEntries(platform, "21.0.8", root, macBundle));
            assertDoesNotThrow(() -> JdkArchiveValidator.validate(zip, platform.target(), 17));
            assertTrue(Files.isRegularFile(zip), "validation must leave its source archive in place");
        }
        final PlatformFixture universalTarget = platforms.get(5);
        final List<TestEntry> universal = runtimeEntries(universalTarget, "21.0.8", "", false);
        replace(universal, "bin/java", universalMachOHeader(true), 0100755);
        assertDoesNotThrow(() -> JdkArchiveValidator.validate(
                writeArchive(universal), universalTarget.target(), 17));
    }

    /**
     * release 元数据和可执行文件头必须分别与目标平台及 feature 下限一致。
     */
    @Test
    void rejectsMismatchedMetadataVersionAndExecutableArchitecture() throws Exception {
        final PlatformFixture linux = new PlatformFixture("linux-amd64", "Linux", "x86_64");
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(
                writeArchive(runtimeEntries(linux, "1.8.0_452", "", false)), linux.target(), 17));

        final PlatformFixture wrongOs = new PlatformFixture("linux-amd64", "Windows", "x86_64");
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(
                writeArchive(runtimeEntries(wrongOs, "21.0.8", "", false)), linux.target(), 8));

        final PlatformFixture wrongBinary = new PlatformFixture("linux-amd64", "Linux", "x86_64");
        final List<TestEntry> entries = runtimeEntries(wrongBinary, "21.0.8", "", false);
        replace(entries, "bin/java", elfHeader("arm64"), 0100755);
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(writeArchive(entries), linux.target(), 8));
        final PlatformFixture armMac = new PlatformFixture("darwin-arm64", "Darwin", "aarch64");
        final List<TestEntry> wrongUniversal = runtimeEntries(armMac, "21.0.8", "", false);
        replace(wrongUniversal, "bin/java", universalMachOHeader(false), 0100755);
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(
                writeArchive(wrongUniversal), armMac.target(), 8));
        assertThrows(IllegalArgumentException.class, () -> JdkArchiveValidator.validate(
                writeArchive(runtimeEntries(linux, "21.0.8", "", false)), "linux-x86", 8));
    }

    /**
     * 路径规范化应拒绝穿越、绝对路径、反斜杠别名、ADS 和大小写冲突。
     */
    @Test
    void rejectsUnsafeAndDuplicateArchivePaths() throws Exception {
        final PlatformFixture linux = new PlatformFixture("linux-amd64", "Linux", "x86_64");
        for (String unsafe : List.of("../escape", "/absolute", "bin/java:stream")) {
            final List<TestEntry> entries = runtimeEntries(linux, "21.0.8", "", false);
            entries.add(new TestEntry(unsafe, new byte[]{1}, 0100644));
            assertThrows(IOException.class,
                    () -> JdkArchiveValidator.validate(writeArchive(entries), linux.target(), 8), unsafe);
        }
        final List<TestEntry> duplicates = runtimeEntries(linux, "21.0.8", "", false);
        duplicates.add(new TestEntry("BIN/JAVA", elfHeader("amd64"), 0100755));
        assertThrows(IOException.class,
                () -> JdkArchiveValidator.validate(writeArchive(duplicates), linux.target(), 8));

        final List<TestEntry> exactDuplicates = runtimeEntries(linux, "21.0.8", "", false);
        exactDuplicates.add(new TestEntry("release", release(linux, "21.0.8"), 0100644));
        assertThrows(IOException.class,
                () -> JdkArchiveValidator.validate(writeArchive(exactDuplicates), linux.target(), 8));
    }

    /**
     * 安全 Unix 链接仍可使用；Windows 链接、越界路径和循环链接必须在打包前失败。
     */
    @Test
    void acceptsOnlyContainedUnixSymlinks() throws Exception {
        final PlatformFixture linux = new PlatformFixture("linux-amd64", "Linux", "x86_64");
        final List<TestEntry> safe = List.of(
                new TestEntry("release", release(linux, "21.0.8"), 0100644),
                new TestEntry("bin/java", "../lib/jexec".getBytes(StandardCharsets.UTF_8), 0120777),
                new TestEntry("lib/jexec", elfHeader("amd64"), 0100755));
        assertDoesNotThrow(() -> JdkArchiveValidator.validate(writeArchive(safe), linux.target(), 8));

        final PlatformFixture windows = new PlatformFixture("windows-amd64", "Windows", "x86_64");
        final List<TestEntry> windowsLink = List.of(
                new TestEntry("release", release(windows, "21.0.8"), 0100644),
                new TestEntry("bin/java.exe", "../lib/jexec.exe".getBytes(StandardCharsets.UTF_8), 0120777),
                new TestEntry("lib/jexec.exe", peHeader("amd64"), 0100644));
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(
                writeArchive(windowsLink), windows.target(), 8));

        final List<TestEntry> escaping = List.of(
                new TestEntry("release", release(linux, "21.0.8"), 0100644),
                new TestEntry("bin/java", "../../outside".getBytes(StandardCharsets.UTF_8), 0120777),
                new TestEntry("outside", elfHeader("amd64"), 0100755));
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(
                writeArchive(escaping), linux.target(), 8));

        final List<TestEntry> cycle = List.of(
                new TestEntry("release", release(linux, "21.0.8"), 0100644),
                new TestEntry("bin/java", "java-alias".getBytes(StandardCharsets.UTF_8), 0120777),
                new TestEntry("bin/java-alias", "java".getBytes(StandardCharsets.UTF_8), 0120777));
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(
                writeArchive(cycle), linux.target(), 8));

        final List<TestEntry> ambiguousSeparator = List.of(
                new TestEntry("release", release(linux, "21.0.8"), 0100644),
                new TestEntry("bin/java", "..\\lib\\jexec".getBytes(StandardCharsets.UTF_8), 0120777),
                new TestEntry("lib/jexec", elfHeader("amd64"), 0100755));
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(
                writeArchive(ambiguousSeparator), linux.target(), 8));
    }

    /**
     * 缺少必需运行时文件或 release 字段时，应拒绝不完整的发行包。
     */
    @Test
    void rejectsMissingRuntimeMetadataAndExecutables() throws Exception {
        final PlatformFixture linux = new PlatformFixture("linux-amd64", "Linux", "x86_64");
        final Path missingJava = writeArchive(List.of(
                new TestEntry("release", release(linux, "21.0.8"), 0100644)));
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(missingJava, linux.target(), 8));
        final Path missingRelease = writeArchive(List.of(
                new TestEntry("bin/java", elfHeader("amd64"), 0100755)));
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(missingRelease, linux.target(), 8));
        final Path missingVersion = writeArchive(List.of(
                new TestEntry("release", "OS_NAME=Linux\nOS_ARCH=x86_64\n".getBytes(StandardCharsets.UTF_8), 0100644),
                new TestEntry("bin/java", elfHeader("amd64"), 0100755)));
        assertThrows(IOException.class, () -> JdkArchiveValidator.validate(missingVersion, linux.target(), 8));
    }

    /**
     * 写入微型 ZIP 夹具，使测试使用与真实发行包相同的中央目录读取逻辑。
     */
    private Path writeArchive(List<TestEntry> entries) throws IOException {
        final Path zip = temporary.resolve("fixture-" + archiveNumber++ + ".zip");
        try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(zip.toFile())) {
            for (TestEntry fixture : entries) {
                final ZipArchiveEntry entry = new ZipArchiveEntry(fixture.path());
                entry.setUnixMode(fixture.mode());
                output.putArchiveEntry(entry);
                output.write(fixture.bytes());
                output.closeArchiveEntry();
            }
        }
        return zip;
    }

    private record PlatformFixture(String target, String osName, String architecture) {
    }

    private record TestEntry(String path, byte[] bytes, int mode) {
    }
}
