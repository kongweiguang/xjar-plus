package io.github.kongweiguang.xjar.plus.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.kongweiguang.xjar.plus.api.ArchiveInfo;
import io.github.kongweiguang.xjar.plus.api.BuildResult;
import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 通过公开接口和真实子 JVM 验证加密产物，避免测试仅覆盖 ZIP 重写。
 *
 * @author kongweiguang
 */
class ExecutableJarCompatibilityTest {
    private static final String PASSWORD = "跨语言兼容-🔒-xjar";
    private static final int MAX_RAW_MULTIPART_BYTES = 1024 * 1024;
    private static final int MAX_RAW_HTTP_HEADER_BYTES = 16 * 1024;
    private static final int RAW_HTTP_CONNECT_TIMEOUT_MILLIS = 2000;
    private static final int RAW_HTTP_READ_TIMEOUT_MILLIS = 10000;
    private static final int RAW_HTTP_OVERALL_TIMEOUT_MILLIS = 15000;
    private static final int MAX_PORT_COLLISION_RETRIES = 3;
    private static final String CDS_WARNING_BODY = "Archived non-system classes are disabled because the "
                                                   + "java.system.class.loader property is specified (value = "
                                                   + "\"io.github.kongweiguang.xjar.plus.runtime.common.XJarPlusSystemClassLoader\"). "
                                                   + "To use archived non-system classes, this property must not be set";
    private static final Pattern CDS_WARNING_LINE = Pattern.compile(
            "(?m)^(?:\\[[0-9.]+s\\]\\[warning\\]\\[cds\\] |OpenJDK 64-Bit Server VM warning: )"
            + Pattern.quote(CDS_WARNING_BODY) + "(?:\\r?\\n|$)");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    /**
     * 将每个已配置运行时作为独立用例，便于定位失败，并避免阻断后续 JDK 的验证。
     */
    private static Stream<Integer> plainJavaMajors() {
        return Stream.of(8, 11, 17, 21, 25)
                .filter(major -> major == 8 || major == 21 || System.getProperty("xjp.java" + major) != null);
    }

    /**
     * 从带版本号的文件名构造测试参数，使持续集成能够保留精确的上游产物。
     */
    private static Stream<Arguments> springBootFixtures() throws IOException {
        List<Path> fixtures = fixtureFiles("boot-[0-9]*.jar").toList();
        assertEquals(Integer.getInteger("xjp.expected.bootFixtures", 16), fixtures.size(),
                "Every selected Spring Boot minor must have a pinned real fixture");
        String selected = System.getProperty("xjp.compatibility.bootVersions", "").trim();
        if (!selected.isEmpty()) {
            List<String> requestedVersions = Arrays.stream(selected.split(","))
                    .map(String::trim).filter(version -> !version.isEmpty()).toList();
            fixtures = fixtures.stream().filter(path -> requestedVersions.contains(bootVersion(path))).toList();
            assertEquals(requestedVersions.size(), fixtures.size(),
                    "Every explicitly selected Boot version must match one pinned fixture: " + requestedVersions);
        }
        return fixtures.stream().map(path -> Arguments.of(
                bootVersion(path), path));
    }

    /**
     * 从样本文件名提取固定补丁版本，并将 RELEASE 后缀保留为版本标识的一部分。
     */
    private static String bootVersion(Path fixture) {
        String name = fixture.getFileName().toString();
        return name.substring("boot-".length(), name.length() - ".jar".length());
    }

    /**
     * 检查上游产物是否真正使用经典加载器，不仅依赖样本文件名判断布局。
     */
    private static void assertClassicBoot32Layout(Path classicPath, Path modernPath) throws IOException {
        try (JarFile classic = new JarFile(classicPath.toFile()); JarFile modern = new JarFile(modernPath.toFile())) {
            assertEquals("org.springframework.boot.loader.launch.JarLauncher",
                    classic.getManifest().getMainAttributes().getValue("Main-Class"),
                    "Boot 3.2 CLASSIC retains the patch's launcher manifest while replacing its loader implementation");
            assertEquals("3.2.12", classic.getManifest().getMainAttributes().getValue("Spring-Boot-Version"),
                    "The layout variant must use the same pinned Boot patch as the modern baseline");
            assertTrue(classic.getJarEntry("org/springframework/boot/loader/LaunchedURLClassLoader.class") != null,
                    "The CLASSIC loader implementation must contain its legacy launched class loader");
            assertTrue(classic.getJarEntry("org/springframework/boot/loader/archive/JarFileArchive.class") != null,
                    "The CLASSIC loader implementation must contain its legacy archive adapter");
            assertTrue(classic.getJarEntry("org/springframework/boot/loader/launch/LaunchedClassLoader.class") == null,
                    "The CLASSIC fixture must not be mistaken for the default modern loader implementation");
            assertTrue(modern.getJarEntry("org/springframework/boot/loader/launch/LaunchedClassLoader.class") != null,
                    "The default Boot 3.2 fixture must prove the comparison uses the modern loader implementation");
            assertTrue(modern.getJarEntry("org/springframework/boot/loader/LaunchedURLClassLoader.class") == null,
                    "The default Boot 3.2 fixture must not be the CLASSIC loader implementation");
        }
    }

    /**
     * 选择两个分别固定版本的 Solon 归档，样本缺失时不能判定测试通过。
     */
    private static Stream<Arguments> solonFixtures() throws IOException {
        List<Path> fixtures = fixtureFiles("solon-*.jar").toList();
        assertEquals(Integer.getInteger("xjp.expected.solonFixtures", 2), fixtures.size(),
                "Every selected Solon 4 minor must have a pinned real fixture");
        return fixtures.stream().map(path -> Arguments.of(
                path.getFileName().toString().substring("solon-".length(), path.getFileName().toString().length() - ".jar".length()),
                path));
    }

    /**
     * 强制要求测试产物存在，避免兼容矩阵配置错误时静默报告成功。
     */
    private static Stream<Path> fixtureFiles(String pattern) throws IOException {
        Path root = fixtureRoot();
        assertTrue(Files.isDirectory(root), "Compatibility fixture directory is missing: " + root);
        try (Stream<Path> paths = Files.list(root)) {
            List<Path> fixtures = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches(pattern.replace(".", "\\.").replace("*", ".*")))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            assertFalse(fixtures.isEmpty(), "No fixtures matched " + pattern + " under " + root);
            return fixtures.stream();
        }
    }

    /**
     * 统一使用脚本传入的样本根目录，并与 Maven 兼容性配置共享该路径。
     */
    private static Path fixtureRoot() {
        String configured = System.getProperty("xjp.compatibility.fixtureDir");
        assertTrue(configured != null && !configured.isBlank(), "Set -Dxjp.compatibility.fixtureDir before running this suite");
        return Path.of(configured).toAbsolutePath().normalize();
    }

    /**
     * 显式校验参数边界和输入输出约定，避免原始与加密 JAR 的共同启动缺陷被相等比较掩盖。
     */
    private static void verifyPlainArchive(Path original, Path java, int major, Path work) throws Exception {
        Files.createDirectories(work);
        Path encrypted = work.resolve("plain-encrypted.jar");
        Path restored = work.resolve("plain-restored.jar");
        Path passwordFile = passwordFile(work);
        String artifact = "plain-java8@java-" + major;
        ArchiveInfo originalInfo = timed(artifact, "inspect", () -> XJarPlus.inspect(original));
        assertEquals("fixture.plain.PlainFixture", originalInfo.mainClass());
        assertFalse(originalInfo.encrypted());

        BuildResult protectedBuild = timed(artifact, "encrypt", () -> XJarPlus.encrypt(request(original, encrypted)));
        assertEquals(encrypted, protectedBuild.output());
        ArchiveInfo protectedInfo = timed(artifact, "verify", () -> XJarPlus.verify(encrypted, PASSWORD.toCharArray()));
        assertTrue(protectedInfo.encrypted());
        assertEquals(originalInfo.mainClass(), protectedInfo.mainClass());
        timed(artifact, "reject-wrong-password", () ->
                assertThrows(IOException.class, () -> XJarPlus.verify(encrypted, "wrong-password".toCharArray())));

        List<String> appArgs = List.of("two words", "quoted\"value", "中文参数");
        ProcessResult plainRun = timed(artifact, "launch-original", () -> run(java, major, original, null, appArgs, new byte[0]));
        ProcessResult encryptedRun = timed(artifact, "launch-protected",
                () -> run(java, major, encrypted, passwordFile, appArgs, new byte[0]));
        assertEquals(0, plainRun.exitCode());
        assertEquals(0, encryptedRun.exitCode(), encryptedRun.diagnosticOutput());
        assertApplicationArguments(plainRun, "original JAR");
        assertApplicationArguments(encryptedRun, "protected JAR");
        assertEquals(plainRun.applicationStdout(), encryptedRun.applicationStdout(),
                "Application stdout differs; protected stderr:\n" + encryptedRun.stderr());
        assertTrue(encryptedRun.applicationStdout().contains("SPI=plain-provider"),
                "Fixture child output:\n" + encryptedRun.diagnosticOutput());
        assertTrue(encryptedRun.applicationStdout().contains("RESOURCE=xjar-plus integration resource — Java 8 fixture"),
                "Fixture child output:\n" + encryptedRun.diagnosticOutput());
        String expectedVersion = major >= 17 ? "version-17" : major >= 9 ? "version-9" : "base-java8";
        assertTrue(encryptedRun.applicationStdout().contains("MR=" + expectedVersion),
                "Java " + major + " must resolve the expected multi-release entry: " + encryptedRun.diagnosticOutput());

        byte[] stdin = "stdin belongs to the application\n".getBytes(StandardCharsets.UTF_8);
        timed(artifact, "application-stdin", () -> {
            ProcessResult stdinRun = run(java, major, encrypted, passwordFile, List.of("--stdin"), stdin);
            assertEquals(0, stdinRun.exitCode(), stdinRun.diagnosticOutput());
            assertArrayEquals(stdin, stdinRun.applicationStdout().getBytes(StandardCharsets.UTF_8),
                    stdinRun.diagnosticOutput());
            return null;
        });
        ProcessResult badPassword = timed(artifact, "reject-wrong-password-launch", () ->
                run(java, major, encrypted, passwordFile(work.resolve("wrong"), "not-the-password"),
                        List.of(), new byte[0]));
        assertNotEquals(0, badPassword.exitCode(), "An incorrect password must fail before application code runs: "
                                                   + badPassword.diagnosticOutput());
        assertFalse(badPassword.applicationStdout().contains("APP=plain-java8"), badPassword.diagnosticOutput());

        timed(artifact, "http-protected", () -> {
            verifyHttp(original, encrypted, java, passwordFile, work, false, false, major, false);
            return null;
        });
        timed(artifact, "decrypt", () -> XJarPlus.decrypt(request(encrypted, restored)));
        timed(artifact, "restore-compare", () -> {
            assertArchivesEqual(original, restored);
            return null;
        });
        ProcessResult restoredRun = timed(artifact, "launch-restored",
                () -> run(java, major, restored, null, appArgs, new byte[0]));
        assertEquals(0, restoredRun.exitCode(), restoredRun.diagnosticOutput());
        assertApplicationArguments(restoredRun, "restored JAR");
        assertEquals(plainRun.applicationStdout(), restoredRun.applicationStdout(),
                "Restored application stdout differs; stderr:\n" + restoredRun.stderr());
    }

    /**
     * 逐项校验启动参数边界，避免原始与加密产物出现相同解析错误时仍被判定兼容。
     */
    private static void assertApplicationArguments(ProcessResult result, String label) {
        List<String> arguments = result.applicationStdout().lines().filter(line -> line.startsWith("ARG=")).toList();
        assertEquals(List.of("ARG=0:two words", "ARG=1:quoted\"value", "ARG=2:中文参数"), arguments,
                label + " must preserve each application argument boundary: " + result.diagnosticOutput());
    }

    /**
     * 比较框架加密前后的真实 HTTP 响应，再验证完整还原后的内容与行为。
     */
    private static void verifyFrameworkArchive(Path original, Path java, Path work, boolean springBoot, int javaMajor,
                                               String artifact, boolean closeDelimitedMultipart) throws Exception {
        Files.createDirectories(work);
        Path encrypted = work.resolve("protected-" + original.getFileName());
        Path restored = work.resolve("restored-" + original.getFileName());
        Path passwordFile = passwordFile(work);
        ArchiveInfo before = timed(artifact, "inspect", () -> XJarPlus.inspect(original));
        assertFalse(before.encrypted());
        timed(artifact, "encrypt", () -> XJarPlus.encrypt(request(original, encrypted)));
        ArchiveInfo after = timed(artifact, "verify", () -> XJarPlus.verify(encrypted, PASSWORD.toCharArray()));
        assertTrue(after.encrypted());
        timed(artifact, "reject-wrong-password", () ->
                assertThrows(IOException.class, () -> XJarPlus.verify(encrypted, "incorrect".toCharArray())));
        timed(artifact, "http-protected", () -> {
            verifyHttp(original, encrypted, java, passwordFile, work, true, springBoot, javaMajor,
                    closeDelimitedMultipart);
            return null;
        });
        timed(artifact, "decrypt", () -> XJarPlus.decrypt(request(encrypted, restored)));
        timed(artifact, "restore-compare", () -> {
            assertArchivesEqual(original, restored);
            assertEquals(before.mainClass(), XJarPlus.inspect(restored).mainClass());
            return null;
        });
        timed(artifact, "http-restored", () -> {
            verifyHttp(original, restored, java, passwordFile, work.resolve("restored-http"), true, springBoot, javaMajor,
                    closeDelimitedMultipart);
            return null;
        });
        assertTrue(springBoot ? after.kind().startsWith("BOOT_") : after.kind().equals("SOLON"));
    }

    /**
     * 仅加密嵌套多版本实现，迫使真实 Boot 启动器认证所选中的 Java 17 物理条目。
     */
    private static void verifyEncryptedBootMultiReleaseEntry(Path original, Path java, Path work, String artifact) throws Exception {
        Path encrypted = work.resolve("protected-boot-mr-only.jar");
        timed(artifact, "encrypt-mr-selected-entries", () -> {
            try (EncryptionRequest request = EncryptionRequest.builder().input(original).output(encrypted)
                    .password(PASSWORD.toCharArray()).encryptDependencies(true)
                    .include("BOOT-INF/lib/xjp-mr-probe.jar!/fixture/mr/BootMultiReleaseProbe.class")
                    .include("BOOT-INF/lib/xjp-mr-probe.jar!/META-INF/versions/9/fixture/mr/BootMultiReleaseProbe.class")
                    .include("BOOT-INF/lib/xjp-mr-probe.jar!/META-INF/versions/17/fixture/mr/BootMultiReleaseProbe.class")
                    .build()) {
                XJarPlus.encrypt(request);
            }
            return null;
        });
        timed(artifact, "verify-mr-artifact", () -> XJarPlus.verify(encrypted, PASSWORD.toCharArray()));
        timed(artifact, "assert-mr-physical-ciphertext", () -> {
            assertFalse(Arrays.equals(nestedEntry(original, "META-INF/versions/17/fixture/mr/BootMultiReleaseProbe.class"),
                            nestedEntry(encrypted, "META-INF/versions/17/fixture/mr/BootMultiReleaseProbe.class")),
                    "The selected physical MR class must be ciphertext in the protected nested dependency");
            return null;
        });
        Path passwordFile = passwordFile(work.resolve("boot-mr-password.txt"), PASSWORD);
        timed(artifact, "mr-runtime-launch-http", () -> {
            try (RunningServer baseline = RunningServer.start(java, 21, original, null, work.resolve("boot-mr-original.log"), true);
                 RunningServer protectedServer = RunningServer.start(java, 21, encrypted, passwordFile,
                         work.resolve("boot-mr-protected.log"), true)) {
                HttpResponse<byte[]> plainValue = request(baseline.uri("/mr-probe"), "GET", Map.of());
                HttpResponse<byte[]> protectedValue = request(protectedServer.uri("/mr-probe"), "GET", Map.of());
                assertEquals(200, plainValue.statusCode());
                assertEquals("version-17", new String(plainValue.body(), StandardCharsets.UTF_8));
                assertArrayEquals(plainValue.body(), protectedValue.body(),
                        "Encrypted nested MR entry must use the JDK-selected physical version");
            }
            return null;
        });
    }

    /**
     * 读取嵌套 JAR 的物理条目，而非 JarFile 按运行时版本选择的视图，确保密文断言准确。
     */
    private static byte[] nestedEntry(Path archivePath, String entryName) throws IOException {
        try (JarFile archive = new JarFile(archivePath.toFile())) {
            JarEntry dependency = archive.getJarEntry("BOOT-INF/lib/xjp-mr-probe.jar");
            assertTrue(dependency != null, "Boot MR dependency is missing from " + archivePath);
            try (ZipInputStream nested = new ZipInputStream(archive.getInputStream(dependency))) {
                ZipEntry entry;
                while ((entry = nested.getNextEntry()) != null) {
                    if (entryName.equals(entry.getName())) {
                        return nested.readAllBytes();
                    }
                }
            }
        }
        throw new IOException("Nested MR entry is missing: " + entryName + " in " + archivePath);
    }

    /**
     * 通过各类加载器验证真实 HTTP 类与资源查找，以及静态资源的长度和范围请求行为。
     */
    private static void verifyHttp(Path baseline, Path candidate, Path java, Path passwordFile, Path work,
                                   boolean framework, boolean springBoot, int javaMajor,
                                   boolean closeDelimitedMultipart) throws Exception {
        Files.createDirectories(work);
        String probePath = "/probe";
        String assetPath = framework ? "/asset.txt" : "/asset";
        try (RunningServer original = RunningServer.start(java, javaMajor, baseline, null, work.resolve("baseline.log"), framework);
             RunningServer protectedServer = RunningServer.start(java, javaMajor, candidate, passwordFile,
                     work.resolve("candidate.log"), framework)) {
            HttpResponse<byte[]> originalProbe = request(original.uri(probePath), "GET", Map.of());
            HttpResponse<byte[]> protectedProbe = request(protectedServer.uri(probePath), "GET", Map.of());
            assertEquals(200, originalProbe.statusCode());
            assertEquals(originalProbe.statusCode(), protectedProbe.statusCode());
            assertArrayEquals(originalProbe.body(), protectedProbe.body());
            String probeBody = new String(protectedProbe.body(), StandardCharsets.UTF_8);
            assertTrue(probeBody.contains("provider"), "SPI provider missing from HTTP probe: " + probeBody);
            assertTrue(probeBody.contains("integration resource"), "Resource missing from HTTP probe: " + probeBody);

            if (springBoot) {
                String expectedMr = javaMajor >= 17 ? "version-17" : javaMajor >= 9 ? "version-9" : "base";
                HttpResponse<byte[]> originalMr = request(original.uri("/mr-probe"), "GET", Map.of());
                HttpResponse<byte[]> protectedMr = request(protectedServer.uri("/mr-probe"), "GET", Map.of());
                assertEquals(200, originalMr.statusCode());
                assertEquals(200, protectedMr.statusCode());
                assertEquals(expectedMr, new String(originalMr.body(), StandardCharsets.UTF_8));
                assertArrayEquals(originalMr.body(), protectedMr.body(), "Boot nested MR class changed after protection");
                assertBootResourceContract(original.uri(""), protectedServer.uri(""), BootFixtureArchive.resources(),
                        closeDelimitedMultipart);
            } else {
                HttpResponse<byte[]> originalAsset = request(original.uri(assetPath), "GET", Map.of());
                HttpResponse<byte[]> protectedAsset = request(protectedServer.uri(assetPath), "GET", Map.of());
                assertEquals(200, originalAsset.statusCode());
                assertEquals(originalAsset.statusCode(), protectedAsset.statusCode());
                assertArrayEquals(originalAsset.body(), protectedAsset.body());
                assertTrue(originalAsset.body().length > 0);
            }
        }
    }

    /**
     * 保留各框架对空静态资源的原生处理，同时要求加密及还原产物保持相同响应状态。
     */
    private static void assertBootResourceContract(URI originalRoot, URI protectedRoot, Map<String, byte[]> resources,
                                                   boolean closeDelimitedMultipart) throws Exception {
        assertEquals(20, resources.get("asset.txt").length, "HTTP fixture must cover the 20-byte boundary");
        assertEquals(0, resources.get("empty.bin").length, "HTTP fixture must cover the empty-resource boundary");
        assertEquals(16, resources.get("block16.bin").length, "HTTP fixture must cover one AES block");
        assertEquals(131089, resources.get("large.bin").length, "HTTP fixture must cover a multi-chunk tail");
        for (Map.Entry<String, byte[]> resource : resources.entrySet()) {
            String path = "/" + resource.getKey();
            byte[] expected = resource.getValue();
            HttpResponse<byte[]> get = request(originalRoot.resolve(path), "GET", Map.of());
            HttpResponse<byte[]> protectedGet = request(protectedRoot.resolve(path), "GET", Map.of());
            boolean emptyStaticResource = "empty.bin".equals(resource.getKey()) && expected.length == 0;
            if (emptyStaticResource) {
                assertTrue(get.statusCode() == 200 || get.statusCode() == 404,
                        "original empty-resource GET must preserve framework-native 200/404 behavior: " + get.statusCode());
            } else {
                assertEquals(200, get.statusCode(), "original GET " + path);
            }
            assertEquals(get.statusCode(), protectedGet.statusCode(), "protected GET status " + path);
            boolean nativeEmptyNotReadable = emptyStaticResource && get.statusCode() == 404;
            if (!nativeEmptyNotReadable) {
                assertArrayEquals(expected, get.body(), "original GET " + path + " body");
                assertArrayEquals(expected, protectedGet.body(), "protected GET " + path + " body");
                assertLength(get, expected.length, "original GET " + path);
                assertLength(protectedGet, expected.length, "protected GET " + path);
            }

            HttpResponse<byte[]> head = request(originalRoot.resolve(path), "HEAD", Map.of());
            HttpResponse<byte[]> protectedHead = request(protectedRoot.resolve(path), "HEAD", Map.of());
            if (emptyStaticResource) {
                assertTrue(head.statusCode() == 200 || head.statusCode() == 404,
                        "original empty-resource HEAD must preserve framework-native 200/404 behavior: " + head.statusCode());
            } else {
                assertEquals(200, head.statusCode(), "original HEAD " + path);
            }
            assertEquals(head.statusCode(), protectedHead.statusCode(), "protected HEAD status " + path);
            assertEquals(0, head.body().length, "original HEAD body " + path);
            assertEquals(0, protectedHead.body().length, "protected HEAD body " + path);
            if (!nativeEmptyNotReadable) {
                assertHeadLengths(head, protectedHead, expected.length, path);
            }

            String originalModified = null;
            String protectedModified = null;
            if (!nativeEmptyNotReadable) {
                originalModified = get.headers().firstValue("Last-Modified")
                        .orElseThrow(() -> new AssertionError("Missing Last-Modified: " + path));
                protectedModified = protectedGet.headers().firstValue("Last-Modified")
                        .orElseThrow(() -> new AssertionError("Missing protected Last-Modified: " + path));
                assertNotModified(request(originalRoot.resolve(path), "GET", Map.of("If-Modified-Since", originalModified)),
                        "original If-Modified-Since " + path);
                assertNotModified(request(protectedRoot.resolve(path), "GET", Map.of("If-Modified-Since", protectedModified)),
                        "protected If-Modified-Since " + path);
            }

            int length = expected.length;
            if (length > 0) {
                int prefixEnd = Math.min(5, length - 1);
                int suffixLength = Math.min(7, length);
                assertRange(request(originalRoot.resolve(path), "GET", Map.of("Range", "bytes=0-" + prefixEnd)),
                        expected, 0, prefixEnd + 1, "original prefix " + path);
                assertRange(request(protectedRoot.resolve(path), "GET", Map.of("Range", "bytes=0-" + prefixEnd)),
                        expected, 0, prefixEnd + 1, "protected prefix " + path);
                assertRange(request(originalRoot.resolve(path), "GET", Map.of("Range", "bytes=-" + suffixLength)),
                        expected, length - suffixLength, length, "original suffix " + path);
                assertRange(request(protectedRoot.resolve(path), "GET", Map.of("Range", "bytes=-" + suffixLength)),
                        expected, length - suffixLength, length, "protected suffix " + path);
                Map<String, String> originalIfRange = Map.of("Range", "bytes=0-" + prefixEnd,
                        "If-Range", originalModified);
                Map<String, String> protectedIfRange = Map.of("Range", "bytes=0-" + prefixEnd,
                        "If-Range", protectedModified);
                assertRange(request(originalRoot.resolve(path), "GET", originalIfRange), expected, 0, prefixEnd + 1,
                        "original matching If-Range " + path);
                assertRange(request(protectedRoot.resolve(path), "GET", protectedIfRange), expected, 0, prefixEnd + 1,
                        "protected matching If-Range " + path);

                Map<String, String> staleIfRange = Map.of("Range", "bytes=0-" + prefixEnd,
                        "If-Range", "Thu, 01 Jan 1970 00:00:00 GMT");
                HttpResponse<byte[]> originalStale = request(originalRoot.resolve(path), "GET", staleIfRange);
                HttpResponse<byte[]> protectedStale = request(protectedRoot.resolve(path), "GET", staleIfRange);
                assertEquals(originalStale.statusCode(), protectedStale.statusCode(), "If-Range baseline behavior " + path);
                assertStaleIfRange(originalStale, expected, prefixEnd + 1, "original stale If-Range " + path);
                assertStaleIfRange(protectedStale, expected, prefixEnd + 1, "protected stale If-Range " + path);
                if (length > 13) {
                    if (closeDelimitedMultipart) {
                        RawHttpResponse originalMultipart = requestCloseDelimitedMultipart(
                                originalRoot.resolve(path), "GET", Map.of("Range", "bytes=0-2,13-16"));
                        RawHttpResponse protectedMultipart = requestCloseDelimitedMultipart(
                                protectedRoot.resolve(path), "GET", Map.of("Range", "bytes=0-2,13-16"));
                        if (length == 131089) {
                            assertMultipart(originalMultipart, expected, "original multipart " + path);
                            assertMultipart(protectedMultipart, expected, "protected multipart " + path);
                            assertEquals(originalMultipart.header("Content-Length"), protectedMultipart.header("Content-Length"),
                                    "Declared multipart length must remain framework-native " + path);
                        } else {
                            assertTrue(List.of(16, 20).contains(length),
                                    "Only the pinned 16-byte and 20-byte legacy resources have a native short multipart entity");
                            NativeMultipartPrefix originalPrefix = assertNativeTruncatedMultipart(originalMultipart, expected,
                                    "native-truncated-multipart original " + path);
                            NativeMultipartPrefix protectedPrefix = assertNativeTruncatedMultipart(protectedMultipart, expected,
                                    "native-truncated-multipart protected " + path);
                            assertEquals(originalPrefix, protectedPrefix,
                                    "Protection must preserve the framework's exact short multipart boundary prefix " + path);
                            System.out.printf("[xjp-native-multipart] resource=%s status=%d declaredLength=%d observedPrefixBytes=%d result=baseline-parity%n",
                                    path, originalPrefix.statusCode(), originalPrefix.declaredLength(), originalPrefix.bodyLength());
                        }
                    } else {
                        assertMultipart(request(originalRoot.resolve(path), "GET", Map.of("Range", "bytes=0-2,13-16")),
                                expected, "original multipart " + path);
                        assertMultipart(request(protectedRoot.resolve(path), "GET", Map.of("Range", "bytes=0-2,13-16")),
                                expected, "protected multipart " + path);
                    }
                }
            }

            if (!nativeEmptyNotReadable) {
                HttpResponse<byte[]> invalid = request(originalRoot.resolve(path), "GET",
                        Map.of("Range", "bytes=" + (length + 19) + "-"));
                HttpResponse<byte[]> protectedInvalid = request(protectedRoot.resolve(path), "GET",
                        Map.of("Range", "bytes=" + (length + 19) + "-"));
                assertEquals(416, invalid.statusCode(), "original out-of-range " + path);
                assertEquals(invalid.statusCode(), protectedInvalid.statusCode(), "protected out-of-range " + path);
                assertEquals("bytes */" + length, invalid.headers().firstValue("Content-Range").orElse(""));
                assertEquals(invalid.headers().firstValue("Content-Range"), protectedInvalid.headers().firstValue("Content-Range"));
            }

            URI originalEtag = originalRoot.resolve("/etag/" + resource.getKey());
            URI protectedEtag = protectedRoot.resolve("/etag/" + resource.getKey());
            HttpResponse<byte[]> tagged = request(originalEtag, "GET", Map.of());
            HttpResponse<byte[]> protectedTagged = request(protectedEtag, "GET", Map.of());
            assertBody(tagged, 200, expected, "original ETag GET " + path);
            assertBody(protectedTagged, 200, expected, "protected ETag GET " + path);
            assertLength(tagged, length, "original ETag GET " + path);
            assertLength(protectedTagged, length, "protected ETag GET " + path);
            String tag = tagged.headers().firstValue("ETag").orElseThrow(() -> new AssertionError("Missing ETag: " + path));
            String protectedTag = protectedTagged.headers().firstValue("ETag")
                    .orElseThrow(() -> new AssertionError("Missing protected ETag: " + path));
            assertEquals(tag, protectedTag, "ETag differs after protection " + path);
            assertNotModified(request(originalEtag, "GET", Map.of("If-None-Match", tag)), "original If-None-Match " + path);
            assertNotModified(request(protectedEtag, "GET", Map.of("If-None-Match", tag)), "protected If-None-Match " + path);
            assertBody(request(originalEtag, "GET", Map.of("If-None-Match", "\"unmatched-fixture-tag\"")),
                    200, expected, "original stale ETag " + path);
            assertBody(request(protectedEtag, "GET", Map.of("If-None-Match", "\"unmatched-fixture-tag\"")),
                    200, expected, "protected stale ETag " + path);
        }
        assertConcurrentGets(originalRoot, protectedRoot, resources);
    }

    /**
     * 要求过期 If-Range 请求符合各框架原始样本已支持的两种响应行为之一。
     */
    private static void assertStaleIfRange(HttpResponse<byte[]> response, byte[] source, int rangeLength, String label) {
        if (response.statusCode() == 206) {
            assertRange(response, source, 0, rangeLength, label);
        } else {
            assertBody(response, 200, source, label);
            assertLength(response, source.length, label);
        }
    }

    /**
     * 同时校验部分响应的字节和明文范围元数据，不能只检查框架选择的状态码。
     */
    private static void assertRange(HttpResponse<byte[]> response, byte[] source, int start, int end, String label) {
        assertBody(response, 206, Arrays.copyOfRange(source, start, end), label);
        assertLength(response, end - start, label);
        assertEquals("bytes " + start + "-" + (end - 1) + "/" + source.length,
                response.headers().firstValue("Content-Range").orElse(""), label + " Content-Range");
    }

    /**
     * 仅对已固定的旧版 Boot 使用读到连接关闭的方式，以兼容其原生多段响应长度缺陷。
     */
    private static boolean usesLegacyMultipartFraming(String version) {
        return List.of("2.1.18", "2.2.13", "2.3.12").contains(version.replace(".RELEASE", ""));
    }

    /**
     * 读取完整回环响应直到流结束，因为固定的 Boot 基线为 MIME 多段响应声明的是资源长度。
     */
    private static RawHttpResponse requestCloseDelimitedMultipart(URI uri, String method,
                                                                  Map<String, String> headers) throws IOException {
        if (!"http".equalsIgnoreCase(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())) {
            throw new IOException("Raw compatibility requests are restricted to the local fixture server: " + uri);
        }
        String target = uri.getRawPath();
        if (uri.getRawQuery() != null) {
            target += "?" + uri.getRawQuery();
        }
        StringBuilder request = new StringBuilder(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                .append("Host: 127.0.0.1:").append(uri.getPort()).append("\r\n");
        headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        request.append("Connection: close\r\n\r\n");

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RAW_HTTP_OVERALL_TIMEOUT_MILLIS);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), RAW_HTTP_CONNECT_TIMEOUT_MILLIS);
            socket.setSoTimeout(RAW_HTTP_READ_TIMEOUT_MILLIS);
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int headerEnd = -1;
            while (true) {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new SocketTimeoutException("Raw multipart response exceeded its overall read deadline: " + uri);
                }
                int remainingMillis = (int) Math.max(1L, Math.min(RAW_HTTP_READ_TIMEOUT_MILLIS,
                        TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                socket.setSoTimeout(remainingMillis);
                int count = socket.getInputStream().read(buffer);
                if (count < 0) {
                    break;
                }
                if (response.size() + count > MAX_RAW_MULTIPART_BYTES) {
                    throw new IOException("Raw multipart response exceeded the 1 MiB safety limit: " + uri);
                }
                response.write(buffer, 0, count);
                byte[] current = response.toByteArray();
                if (headerEnd < 0) {
                    headerEnd = indexOfHeaderEnd(current);
                    if (headerEnd < 0 && current.length > MAX_RAW_HTTP_HEADER_BYTES) {
                        throw new IOException("Raw HTTP response headers exceeded the 16 KiB safety limit: " + uri);
                    }
                    if (headerEnd >= 0 && headerEnd > MAX_RAW_HTTP_HEADER_BYTES) {
                        throw new IOException("Raw HTTP response headers exceeded the 16 KiB safety limit: " + uri);
                    }
                }
            }
            byte[] wire = response.toByteArray();
            if (headerEnd < 0) {
                throw new IOException("Raw HTTP response ended before its header terminator: " + uri);
            }
            return parseRawHttpResponse(wire, headerEnd, uri);
        }
    }

    /**
     * 直接定位 HTTP 头部结束标记，避免将任意二进制响应内容解码为文本。
     */
    private static int indexOfHeaderEnd(byte[] wire) {
        for (int index = 0; index <= wire.length - 4; index++) {
            if (wire[index] == '\r' && wire[index + 1] == '\n' && wire[index + 2] == '\r' && wire[index + 3] == '\n') {
                return index;
            }
        }
        return -1;
    }

    /**
     * 只解析小型 HTTP/1.1 头部，正文始终保留为原始字节。
     */
    private static RawHttpResponse parseRawHttpResponse(byte[] wire, int headerEnd, URI uri) throws IOException {
        String headerBlock = new String(wire, 0, headerEnd, StandardCharsets.ISO_8859_1);
        String[] lines = headerBlock.split("\\r\\n", -1);
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/1.")) {
            throw new IOException("Malformed HTTP status line for " + uri + ": " + lines[0]);
        }
        int statusCode;
        try {
            statusCode = Integer.parseInt(status[1]);
        } catch (NumberFormatException failure) {
            throw new IOException("Malformed HTTP status code for " + uri + ": " + lines[0], failure);
        }
        Map<String, String> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("Malformed HTTP header for " + uri + ": " + line);
            }
            String name = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            responseHeaders.merge(name, value, (first, next) -> first + ", " + next);
        }
        int bodyStart = headerEnd + 4;
        byte[] body = Arrays.copyOfRange(wire, bodyStart, wire.length);
        return new RawHttpResponse(statusCode, responseHeaders, body);
    }

    /**
     * 将旧版短资源缺陷限定为声明长度对应的精确前缀，不把截断结果误认为有效的多段实体。
     */
    private static NativeMultipartPrefix assertNativeTruncatedMultipart(RawHttpResponse response, byte[] source, String label) {
        assertEquals(206, response.statusCode(), label + " status");
        String connection = response.header("Connection").orElse("").toLowerCase(Locale.ROOT);
        assertTrue(connection.contains("close"), label + " must close after the truncated native entity");
        long declaredLength = response.header("Content-Length")
                .map(value -> Long.parseLong(value.trim()))
                .orElseThrow(() -> new AssertionError(label + " missing Content-Length"));
        assertEquals((long) source.length, declaredLength, label + " must expose the original resource length");
        assertEquals(source.length, response.body().length, label + " must stop at the advertised-length boundary");
        assertFalse(response.header("Transfer-Encoding").isPresent(), label + " must remain close-delimited, not chunked");

        String contentType = response.header("Content-Type").orElse("");
        String boundary = multipartBoundary(contentType, label);
        String mediaType = contentType.substring(0, contentType.indexOf(';')).trim().toLowerCase(Locale.ROOT);
        assertEquals("multipart/byteranges", mediaType, label + " media type");
        String wirePrefix = new String(response.body(), StandardCharsets.ISO_8859_1);
        String expectedPrefix = "\r\n--" + boundary;
        assertTrue(response.body().length < expectedPrefix.length(),
                label + " must be cut before the first part headers for these pinned small resources");
        assertEquals(expectedPrefix.substring(0, response.body().length), wirePrefix,
                label + " must be the exact CRLF plus multipart-boundary prefix");
        return new NativeMultipartPrefix(response.statusCode(), declaredLength, response.body().length, connection,
                contentType.replace(boundary, "<boundary>"));
    }

    /**
     * 以 ISO-8859-1 解析 MIME 边界，避免有损文本解码影响任意二进制字节的校验。
     */
    private static void assertMultipart(HttpResponse<byte[]> response, byte[] source, String label) {
        assertEquals(206, response.statusCode(), label + " status");
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        response.headers().firstValueAsLong("Content-Length").ifPresent(length ->
                assertEquals((long) response.body().length, length, label + " Content-Length"));
        assertMultipartBody(contentType, response.body(), source, label);
    }

    /**
     * 保留框架声明的资源长度，同时校验以连接关闭为终点读取的完整 MIME 实体。
     */
    private static void assertMultipart(RawHttpResponse response, byte[] source, String label) {
        assertEquals(206, response.statusCode(), label + " status");
        assertTrue(response.header("Connection").orElse("").toLowerCase(Locale.ROOT).contains("close"),
                label + " must explicitly close the response connection");
        long declaredLength = response.header("Content-Length")
                .map(value -> Long.parseLong(value.trim()))
                .orElseThrow(() -> new AssertionError(label + " missing Content-Length"));
        assertEquals((long) source.length, declaredLength,
                label + " must retain the framework-native full-resource Content-Length");
        assertFalse(response.header("Transfer-Encoding").isPresent(),
                label + " must be close-delimited rather than chunked");
        assertMultipartBody(response.header("Content-Type").orElse(""), response.body(), source, label);
    }

    /**
     * 独立校验精确字节范围和 MIME 结束标记，不依赖随机生成的边界值。
     */
    private static void assertMultipartBody(String contentType, byte[] body, byte[] source, String label) {
        assertTrue(contentType.toLowerCase(Locale.ROOT).startsWith("multipart/byteranges;"), label + " Content-Type=" + contentType);
        String boundary = multipartBoundary(contentType, label);
        String wire = new String(body, StandardCharsets.ISO_8859_1);
        String[] sections = wire.split(Pattern.quote("--" + boundary), -1);
        assertEquals(4, sections.length, label + " must contain two parts");
        assertTrue(sections[0].isEmpty() || "\r\n".equals(sections[0]), label + " unexpected preamble");
        assertTrue("--".equals(sections[3]) || "--\r\n".equals(sections[3]), label + " missing final boundary");
        int[][] positions = {{0, 3}, {13, Math.min(17, source.length)}};
        for (int index = 0; index < positions.length; index++) {
            String part = sections[index + 1];
            assertTrue(part.startsWith("\r\n") && part.endsWith("\r\n"), label + " malformed part " + index);
            part = part.substring(2, part.length() - 2);
            int headersEnd = part.indexOf("\r\n\r\n");
            assertTrue(headersEnd >= 0, label + " missing part headers " + index);
            String expectedRange = "bytes " + positions[index][0] + "-" + (positions[index][1] - 1) + "/" + source.length;
            List<String> contentRanges = part.substring(0, headersEnd).lines()
                    .filter(line -> line.regionMatches(true, 0, "Content-Range:", 0, "Content-Range:".length()))
                    .map(line -> line.substring("Content-Range:".length()).trim()).toList();
            assertEquals(List.of(expectedRange), contentRanges, label + " unexpected Content-Range " + index);
            byte[] actual = part.substring(headersEnd + 4).getBytes(StandardCharsets.ISO_8859_1);
            assertArrayEquals(Arrays.copyOfRange(source, positions[index][0], positions[index][1]), actual,
                    label + " payload " + index);
        }
    }

    /**
     * 统一提取 MIME 边界，使完整响应与原生截断响应使用一致的头部校验。
     */
    private static String multipartBoundary(String contentType, String label) {
        var boundaryMatch = Pattern.compile("(?:^|;)\\s*boundary=(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE)
                .matcher(contentType);
        assertTrue(boundaryMatch.find(), label + " missing multipart boundary in Content-Type=" + contentType);
        return boundaryMatch.group(1) != null ? boundaryMatch.group(1) : boundaryMatch.group(2);
    }

    /**
     * 即使 Spring 5.3 原生忽略空静态资源，也要求并发请求的响应状态保持一致。
     */
    private static void assertConcurrentGets(URI originalRoot, URI protectedRoot, Map<String, byte[]> resources) throws Exception {
        for (Map.Entry<String, byte[]> resource : resources.entrySet()) {
            URI originalUri = originalRoot.resolve("/" + resource.getKey());
            URI protectedUri = protectedRoot.resolve("/" + resource.getKey());
            List<CompletableFuture<HttpResponse<byte[]>>> calls = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                calls.add(HTTP.sendAsync(HttpRequest.newBuilder(originalUri).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()));
                calls.add(HTTP.sendAsync(HttpRequest.newBuilder(protectedUri).timeout(Duration.ofSeconds(5)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray()));
            }
            try {
                CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
                boolean emptyResource = "empty.bin".equals(resource.getKey()) && resource.getValue().length == 0;
                int originalStatus = calls.get(0).join().statusCode();
                if (emptyResource) {
                    assertTrue(originalStatus == 200 || originalStatus == 404,
                            "concurrent empty-resource baseline must be 200 or 404: " + originalStatus);
                } else {
                    assertEquals(200, originalStatus, "concurrent baseline " + resource.getKey());
                }
                for (int index = 0; index < calls.size(); index++) {
                    HttpResponse<byte[]> response = calls.get(index).join();
                    int expectedStatus = index % 2 == 0 ? originalStatus : calls.get(index - 1).join().statusCode();
                    assertEquals(expectedStatus, response.statusCode(), "concurrent status " + resource.getKey());
                    if (response.statusCode() == 200) {
                        assertArrayEquals(resource.getValue(), response.body(), "concurrent GET " + resource.getKey());
                        assertLength(response, resource.getValue().length, "concurrent GET " + resource.getKey());
                    }
                }
            } catch (Exception failure) {
                calls.forEach(call -> call.cancel(true));
                throw failure;
            }
        }
    }

    /**
     * 同时检查字节内容与状态码，避免错误响应仅因长度相同而通过校验。
     */
    private static void assertBody(HttpResponse<byte[]> response, int status, byte[] expected, String label) {
        assertEquals(status, response.statusCode(), label + " status");
        assertArrayEquals(expected, response.body(), label + " body");
    }

    /**
     * 显式校验资源长度，因为加密包装必须公开明文长度，不能公开密文长度。
     */
    private static void assertLength(HttpResponse<byte[]> response, long expected, String label) {
        assertEquals(expected, response.headers().firstValueAsLong("Content-Length").orElse(-1), label + " Content-Length");
    }

    /**
     * 保留框架原有 HEAD 头部的存在性，并逐项核对已声明的长度是否等于明文大小。
     */
    private static void assertHeadLengths(HttpResponse<byte[]> baseline, HttpResponse<byte[]> candidate,
                                          long expected, String path) {
        var baselineLength = baseline.headers().firstValueAsLong("Content-Length");
        var candidateLength = candidate.headers().firstValueAsLong("Content-Length");
        baselineLength.ifPresent(actual -> assertEquals(expected, actual, "original HEAD " + path + " Content-Length"));
        if (baselineLength.isPresent()) {
            assertTrue(candidateLength.isPresent(), "protected HEAD omitted baseline Content-Length " + path);
        }
        candidateLength.ifPresent(actual ->
                assertEquals(expected, actual, "protected HEAD " + path + " Content-Length"));
    }

    /**
     * 条件响应正文必须为空，避免只检查状态码而漏掉意外输出的字节。
     */
    private static void assertNotModified(HttpResponse<byte[]> response, String label) {
        assertEquals(304, response.statusCode(), label + " status");
        assertEquals(0, response.body().length, label + " body");
    }

    /**
     * 构造不可变接口请求，确保各加密路径使用完全一致的口令和源归档。
     */
    private static EncryptionRequest request(Path input, Path output) {
        return EncryptionRequest.builder().input(input).output(output).password(PASSWORD.toCharArray()).build();
    }

    /**
     * 将公开测试口令写入 UTF-8 文件，因为加密应用的标准输入应由业务程序使用。
     */
    private static Path passwordFile(Path directory) throws IOException {
        return passwordFile(directory.resolve("password.txt"), PASSWORD);
    }

    /**
     * 创建末尾带一个换行的私有输入文件，以验证文档约定的口令文件换行处理规则。
     */
    private static Path passwordFile(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value + "\n", StandardCharsets.UTF_8);
        return path;
    }

    /**
     * 将子进程诊断与业务输出分开，因为 JVM 警告不属于应用输出。
     */
    private static ProcessResult run(Path java, int javaMajor, Path jar, Path passwordFile,
                                     List<String> args, byte[] stdin) throws Exception {
        Process process = ExternalJava.start(new ProcessBuilder(
                ExternalJava.jarCommand(java, javaMajor, jar, passwordFile, args)));
        CompletableFuture<String> stdout = readOutputAsync(process.getInputStream());
        CompletableFuture<String> stderr = readOutputAsync(process.getErrorStream());
        try {
            process.getOutputStream().write(stdin);
            process.getOutputStream().close();
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Application process timed out: " + jar);
            return new ProcessResult(process.exitValue(), stdout.get(5, TimeUnit.SECONDS),
                    stderr.get(5, TimeUnit.SECONDS));
        } finally {
            ExternalJava.terminateTree(process);
        }
    }

    /**
     * 并发读取各进程管道，避免大量诊断输出堵塞管道，使有超时限制的进程等待陷入死锁。
     */
    private static CompletableFuture<String> readOutputAsync(InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try (input) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException("Could not capture child JVM output", failure);
            }
        });
    }

    /**
     * 分别打开各 ZIP，比较条目名与解压后字节，忽略无关的压缩元数据。
     */
    private static void assertArchivesEqual(Path expected, Path actual) throws IOException {
        Map<String, String> expectedEntries;
        Map<String, String> actualEntries;
        try (JarFile left = new JarFile(expected.toFile()); JarFile right = new JarFile(actual.toFile())) {
            expectedEntries = ArchiveComparison.entryDigests(left);
            actualEntries = ArchiveComparison.entryDigests(right);
        }
        assertEquals(expectedEntries, actualEntries, "Restored archive entries must match by path and plaintext bytes");
    }

    /**
     * 为每次 HTTP 请求设置超时，避免资源断言因子服务无响应而一直等待。
     */
    private static HttpResponse<byte[]> request(URI uri, String method, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5));
        headers.forEach(builder::header);
        builder.method(method, HttpRequest.BodyPublishers.noBody());
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * 仅输出不含秘密的阶段耗时，以区分归档处理开销和框架启动耗时。
     */
    private static <T> T timed(String artifact, String phase, TimedOperation<T> operation) throws Exception {
        long started = System.nanoTime();
        boolean completed = false;
        try {
            T result = operation.run();
            completed = true;
            return result;
        } finally {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.printf("[xjp-compat-stage] artifact=%s phase=%s result=%s elapsedMs=%d%n",
                    artifact, phase, completed ? "completed" : "failed", elapsedMillis);
        }
    }

    /**
     * 隔离各 JDK 的失败，避免单个不受支持的运行时阻断其余兼容矩阵。
     */
    @ParameterizedTest(name = "Plain Java 8 bytecode on Java {0}")
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @EnabledIfSystemProperty(named = "xjp.compatibility.plain", matches = "true")
    @MethodSource("plainJavaMajors")
    void plainExecutableJarPreservesArgumentsStdinSpiAndResources(
            int major, @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path work) throws Exception {
        Path base = fixtureRoot().resolve("plain-java8.jar");
        assertTrue(Files.isRegularFile(base), "Build the plain Java 8 fixture before compatibility verification");
        Path original = MultiReleaseJarFixture.create(base, work);
        try (JarFile archive = new JarFile(original.toFile())) {
            JarEntry mainClass = archive.getJarEntry("fixture/plain/PlainFixture.class");
            assertTrue(mainClass != null, "Java 8 fixture main class must be present");
            try (var input = archive.getInputStream(mainClass)) {
                byte[] header = input.readNBytes(8);
                assertEquals(52, ((header[6] & 0xff) << 8) | (header[7] & 0xff), "Fixture bytecode must target Java 8");
            }
        }

        Path java = ExternalJava.executable(major);
        timed("plain-java8@java-" + major, "archive-round-trip", () -> {
            verifyPlainArchive(original, java, major, work.resolve("java-" + major));
            return null;
        });
    }

    /**
     * 限制真实 Boot 进程的运行时间，仅为失败的小版本用例保留私有测试证据。
     */
    @ParameterizedTest(name = "Spring Boot {0}")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @EnabledIfSystemProperty(named = "xjp.compatibility.boot", matches = "true")
    @MethodSource("springBootFixtures")
    void springBootExecutablePreservesHttpSpiAndRestoration(String version, Path original,
                                                            @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path work) throws Exception {
        int javaMajor = version.startsWith("2.") ? 8 : 21;
        String artifact = "boot-" + version;
        boolean closeDelimitedMultipart = usesLegacyMultipartFraming(version);
        Path withContract = timed(artifact, "fixture-injection", () ->
                BootFixtureArchive.prepare(original, work.resolve("boot-fixture.jar")));
        verifyFrameworkArchive(withContract, ExternalJava.executable(javaMajor), work, true, javaMajor,
                artifact, closeDelimitedMultipart);
        if ("4.1.2".equals(version)) {
            timed(artifact, "encrypted-mr-runtime", () -> {
                verifyEncryptedBootMultiReleaseEntry(withContract, ExternalJava.executable(21), work, artifact);
                return null;
            });
        }
    }

    /**
     * 单独验证 Boot 3.2 可选的经典加载器，避免将布局差异误认为另一个小版本。
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @EnabledIfSystemProperty(named = "xjp.compatibility.boot.layoutVariants", matches = "true")
    void springBootClassicLayoutPreservesHttpSpiAndRestoration(
            @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path work)
            throws Exception {
        Path original = fixtureRoot().resolve("boot-layout-3.2.12-classic.jar");
        Path modern = fixtureRoot().resolve("boot-3.2.12.jar");
        assertTrue(Files.isRegularFile(original), "Build the separate Boot 3.2.12 CLASSIC fixture before verification");
        assertTrue(Files.isRegularFile(modern), "Build the default Boot 3.2.12 fixture for an explicit layout comparison");
        assertClassicBoot32Layout(original, modern);
        String artifact = "boot-layout-3.2.12-classic";
        Path withContract = timed(artifact, "fixture-injection", () ->
                BootFixtureArchive.prepare(original, work.resolve("boot-fixture.jar")));
        verifyFrameworkArchive(withContract, ExternalJava.executable(21), work, true, 21, artifact, false);
    }

    /**
     * 限制 Solon 子进程生命周期，仅为失败的嵌套 JAR 用例保留私有测试证据。
     */
    @ParameterizedTest(name = "Solon {0}")
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @EnabledIfSystemProperty(named = "xjp.compatibility.solon", matches = "true")
    @MethodSource("solonFixtures")
    void solonExecutablePreservesHttpSpiAndRestoration(String version, Path original,
                                                       @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path work) throws Exception {
        verifyFrameworkArchive(original, ExternalJava.executable(21), work, false, 21,
                "solon-" + version, false);
    }

    /**
     * 保留测试阶段的受检异常约定，避免耗时记录改变异常或断言语义。
     */
    @FunctionalInterface
    private interface TimedOperation<T> {
        /**
         * 执行单个集成阶段，并将受检异常原样交给调用方测试处理。
         */
        T run() throws Exception;
    }



    /**
     * 将原始正文与 HTTP 帧结构分开保存，使基线的协议分帧缺陷保持可观察。
     */
    private record RawHttpResponse(int statusCode, Map<String, String> headers, byte[] body) {
        /**
         * 按不区分大小写的方式查找头部，同时将缺失保留为独立状态。
         */
        private Optional<String> header(String name) {
            return Optional.ofNullable(headers.get(name));
        }
    }

    /**
     * 保存归一化的传输证据，用于识别已固定版本中的短资源多段响应截断。
     */
    private record NativeMultipartPrefix(int statusCode, long declaredLength, int bodyLength,
                                         String connection, String normalizedContentType) {
    }
    /** 将业务标准输出与诊断分开保存，使相等性比较遵循应用的输入输出约定。 */
    private record ProcessResult(int exitCode, String stdout, String stderr) {
        /**
         * 仅移除已确认的 CDS 提示行，同时在诊断结果中保留原始输出流。
         */
        private String applicationStdout() {
            return CDS_WARNING_LINE.matcher(stdout).replaceAll("");
        }

        /**
         * 仅在失败详情中合并输出通道，应用输出比较仍保持通道独立。
         */
        private String diagnosticOutput() {
            return "stdout:\n" + stdout + "\nstderr:\n" + stderr;
        }
    }

    /**
     * 统一管理一个子 JVM 及其日志，既支持就绪轮询，也确保断言失败时执行清理。
     */
    static final class RunningServer implements AutoCloseable {
        private final Process process;
        private final Path log;
        private final int port;

        /**
         * 将子进程句柄与私有日志集中管理，使测试清理行为确定且可控。
         */
        private RunningServer(Process process, Path log, int port) {
            this.process = process;
            this.log = log;
            this.port = port;
        }

        /**
         * 启动归档；若有界清理也失败，仍将启动诊断保留为主要错误。
         */
        private static RunningServer start(Path java, int javaMajor, Path jar, Path passwordFile, Path log,
                                           boolean framework) throws Exception {
            int collisionRetries = 0;
            while (true) {
                int port = availablePort();
                List<String> args = framework
                        ? List.of("--server.port=" + port)
                        : List.of("--serve", "--port=" + port);
                List<String> command = ExternalJava.jarCommand(java, javaMajor, jar, passwordFile, args);
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.redirectErrorStream(true).redirectOutput(log.toFile());
                Process process = ExternalJava.start(builder);
                RunningServer server = new RunningServer(process, log, port);
                try {
                    server.awaitReady();
                    return server;
                } catch (Exception failure) {
                    boolean childExited = !process.isAlive();
                    boolean portCollision = hasPortCollisionEvidence(server.boundedLogTail(log), port);
                    boolean cleanupComplete = true;
                    try {
                        server.close();
                    } catch (Exception cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                        cleanupComplete = false;
                    }
                    if (cleanupComplete && shouldRetryPortCollision(childExited, portCollision, collisionRetries)) {
                        collisionRetries++;
                        continue;
                    }
                    throw failure;
                }
            }
        }

        /**
         * 将可明确识别的端口绑定冲突与认证、请求处理及其他启动失败区分开。
         */
        static boolean hasPortCollisionEvidence(String log, int port) {
            String normalized = log.toLowerCase(Locale.ROOT);
            String selectedPort = Integer.toString(port);
            return normalized.contains("address already in use")
                   || normalized.contains("port " + selectedPort + " is already in use")
                   || normalized.contains("port " + selectedPort + " was already in use");
        }

        /**
         * 将重试条件独立出来便于测试，并要求上一进程已退出且最多重试三次。
         */
        static boolean shouldRetryPortCollision(boolean childExited, boolean portCollision, int retries) {
            return childExited && portCollision && retries < MAX_PORT_COLLISION_RETRIES;
        }

        /**
         * 先绑定再释放临时套接字以选择端口，避免固定测试端口造成跨轮次冲突。
         */
        private static int availablePort() throws IOException {
            try (ServerSocket socket = new ServerSocket(0)) {
                socket.setReuseAddress(true);
                return socket.getLocalPort();
            }
        }

        /**
         * 仅构造回环地址 URI，避免兼容性测试向外部暴露样本服务。
         */
        private URI uri(String path) {
            return URI.create("http://127.0.0.1:" + port + path);
        }

        /**
         * 仅重试启动期间的连接拒绝；服务端错误立即失败，避免异常处理器持续刷日志。
         */
        private void awaitReady() throws Exception {
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw readinessFailure("Application exited before HTTP readiness (exitCode="
                                           + process.exitValue() + ")");
                }
                try {
                    HttpResponse<byte[]> response = request(uri("/probe"), "GET", Map.of());
                    if (response.statusCode() == 200) {
                        return;
                    }
                    if (response.statusCode() >= 500) {
                        throw readinessFailure("HTTP readiness probe returned server error " + response.statusCode());
                    }
                } catch (IOException unavailable) {
                    if (!isStartupConnectionRefusal(unavailable)) {
                        throw readinessFailure("HTTP readiness probe failed with non-retryable "
                                               + unavailable.getClass().getSimpleName());
                    }
                    Thread.sleep(200);
                }
            }
            throw readinessFailure("Application did not become HTTP-ready within 60 seconds");
        }

        /**
         * 限制断言报告大小，并在单独归档的路径保留完整子进程日志。
         */
        private IllegalStateException readinessFailure(String reason) {
            return new IllegalStateException(reason + "; child log: " + log + "\nLast child output (bounded):\n"
                                             + boundedLogTail(log));
        }

        /**
         * 仅重试绑定前的连接拒绝；流结束或连接重置表示请求已被接受但探测处理失败。
         */
        private boolean isStartupConnectionRefusal(IOException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConnectException) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 只读取有字节上限的日志尾部，避免重复框架异常导致 Surefire XML 无限膨胀。
         */
        private String boundedLogTail(Path source) {
            final int maximumBytes = 16 * 1024;
            final int maximumLines = 80;
            try (RandomAccessFile input = new RandomAccessFile(source.toFile(), "r")) {
                long length = input.length();
                long start = Math.max(0, length - maximumBytes);
                byte[] bytes = new byte[(int) (length - start)];
                input.seek(start);
                input.readFully(bytes);
                String tail = new String(bytes, StandardCharsets.UTF_8);
                if (start > 0) {
                    int lineBreak = tail.indexOf('\n');
                    tail = lineBreak < 0 ? "" : tail.substring(lineBreak + 1);
                }
                String[] lines = tail.split("\\R", -1);
                int firstLine = Math.max(0, lines.length - maximumLines);
                String output = String.join(System.lineSeparator(), Arrays.copyOfRange(lines, firstLine, lines.length));
                return (start > 0 ? "[earlier output truncated; full log preserved]\n" : "") + output;
            } catch (IOException failure) {
                return "[child log unavailable: " + failure.getClass().getSimpleName() + "]";
            }
        }

        /**
         * 在 JUnit 删除目录前回收 JVM 进程树、归档输出并释放临时日志文件。
         */
        @Override
        public void close() throws Exception {
            Throwable failure = null;
            boolean reaped = false;
            try {
                ExternalJava.terminateTree(process);
                reaped = true;
            } catch (Exception | AssertionError terminationFailure) {
                failure = terminationFailure;
            }
            failure = closeStreams(failure);
            if (reaped) {
                Path preservedLog = null;
                try {
                    preservedLog = preserveLog();
                } catch (IOException preserveFailure) {
                    failure = combine(failure, preserveFailure);
                }
                if (preservedLog != null) {
                    try {
                        deleteLogWithRetry(preservedLog);
                    } catch (IOException deleteFailure) {
                        failure = combine(failure, deleteFailure);
                    }
                }
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof AssertionError assertionError) {
                throw assertionError;
            }
        }

        /**
         * 回收进程后关闭父进程侧的全部管道，避免测试句柄延迟 Windows 日志清理。
         */
        private Throwable closeStreams(Throwable failure) {
            for (AutoCloseable stream : List.of(process.getOutputStream(), process.getInputStream(), process.getErrorStream())) {
                try {
                    stream.close();
                } catch (Exception closeFailure) {
                    failure = combine(failure, closeFailure);
                }
            }
            return failure;
        }

        /**
         * 在 JUnit 清理临时目录前，将完整子进程输出复制到该目录之外。
         */
        private Path preserveLog() throws IOException {
            if (!Files.isRegularFile(log)) {
                throw new IOException("Child JVM log is missing; expected it at " + log);
            }
            Path directory = Path.of(System.getProperty("xjp.compatibility.logs", "target/compatibility-server-logs"))
                    .toAbsolutePath().normalize();
            Files.createDirectories(directory);
            Path preserved = directory.resolve(UUID.randomUUID() + "-" + log.getFileName());
            Files.copy(log, preserved, StandardCopyOption.COPY_ATTRIBUTES);
            return preserved;
        }

        /**
         * 在限定时间内重试 Windows 临时共享冲突，并明确报告持续存在的句柄泄漏。
         */
        private void deleteLogWithRetry(Path preservedLog) throws IOException {
            boolean interrupted = Thread.interrupted();
            IOException lastFailure = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            try {
                while (true) {
                    try {
                        Files.deleteIfExists(log);
                        return;
                    } catch (IOException deletionFailure) {
                        lastFailure = deletionFailure;
                    }
                    if (System.nanoTime() >= deadline) {
                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException cancellation) {
                        interrupted = true;
                    }
                }
                String retained = preservedLog == null ? "; original log remains at " + log
                        : "; diagnostic copy is at " + preservedLog;
                throw new IOException("Could not release temporary child JVM log after bounded retries" + retained,
                        lastFailure);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * 以首个生命周期错误为主，并将后续清理错误作为受抑制异常保留。
         */
        private Throwable combine(Throwable current, Throwable additional) {
            if (current == null) {
                return additional;
            }
            current.addSuppressed(additional);
            return current;
        }
    }
}
