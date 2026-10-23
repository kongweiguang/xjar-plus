/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.ArchiveCommand;
import io.github.kongweiguang.xjar.plus.domain.model.ArchiveSummary;
import io.github.kongweiguang.xjar.plus.domain.model.BuildArtifact;
import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import io.github.kongweiguang.xjar.plus.domain.model.LauncherCommand;
import io.github.kongweiguang.xjar.plus.domain.model.LicenseIssueCommand;
import io.github.kongweiguang.xjar.plus.domain.port.ArchiveOperations;
import io.github.kongweiguang.xjar.plus.format.IssuerCredentials;
import io.github.kongweiguang.xjar.plus.format.LicenseCodec;
import io.github.kongweiguang.xjar.plus.infrastructure.distribution.GoCompiler;
import io.github.kongweiguang.xjar.plus.infrastructure.distribution.LauncherTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分发测试将不依赖编译器的约束单独验证，使常规单元测试无需安装 Go。
 */
final class GoDistributionOperationsTest {
    private static final String PLAIN_SYSTEM_CLASS_LOADER =
            "-Djava.system.class.loader=io.github.kongweiguang.xjar.plus.runtime.common.XJarPlusSystemClassLoader";

    @TempDir
    private Path temporary;

    /**
     * 续期必须复用应用标识和密钥材料，同时拒绝覆盖发布者凭据。
     */
    @Test
    void issuerAndLicenseAreAtomicAndReusable() throws Exception {
        final var operations = new GoDistributionOperations(new InspectOnlyArchives());
        final Path issuerFile = temporary.resolve("publisher.issuer");
        operations.createIssuer(issuerFile, "中文 app 🔒");
        final byte[] original = Files.readAllBytes(issuerFile);
        assertThrows(IOException.class, () -> operations.createIssuer(issuerFile, "other"));
        assertArrayEquals(original, Files.readAllBytes(issuerFile));
        final IssuerCredentials credentials = IssuerCredentials.parse(original);
        final Instant activation = Instant.parse("2026-01-01T00:00:00Z");
        final Instant expiration = Instant.parse("2030-01-01T00:00:00Z");
        final Path licenseFile = temporary.resolve("key.x");
        operations.issueLicense(new LicenseIssueCommand(issuerFile, licenseFile, activation, expiration));
        final var grant = LicenseCodec.open(Files.readAllBytes(licenseFile),
                credentials.getLicenseKey(), credentials.getAppId());
        assertEquals(credentials.getAppId(), grant.getAppId());
        assertTrue(grant.isValidAt(activation.getEpochSecond()));
        assertFalse(grant.isValidAt(expiration.getEpochSecond()));
        assertThrows(IOException.class, () -> operations.issueLicense(
                new LicenseIssueCommand(issuerFile, licenseFile, activation, null)));
    }

    /**
     * 在启动 Go 或提交任何打包暂存内容前拒绝应用标识不匹配的凭据。
     */
    @Test
    void invalidIssuerFailsBeforeBuildSideEffects() throws Exception {
        final var operations = new GoDistributionOperations(new InspectOnlyArchives());
        final Path issuerFile = temporary.resolve("publisher.issuer");
        operations.createIssuer(issuerFile, "first-app");
        final var archive = new ArchiveCommand(temporary.resolve("original.jar"), null, "fixture-only".toCharArray(),
                List.of(), List.of(), false, (stage, percent) -> {
        }, CancellationProbe.NEVER);
        final Path output = temporary.resolve("dist");
        final var settings = new LauncherCommand.BuildSettings(output, "windows-amd64", issuerFile, "other-app",
                null, temporary.resolve("not-a-go-compiler"), List.of(), List.of());
        final var term = new LauncherCommand.LicenseTerm(Instant.now(), null);
        final var command = new LauncherCommand(archive, settings, term);
        assertThrows(IOException.class, () -> operations.packageLauncher(command));
        assertFalse(Files.exists(output));
    }

    /**
     * 最低 Java 版本未知或超出支持范围时，必须在读取凭据或启动编译器前失败。
     */
    @Test
    void unknownJavaFloorFailsBeforeBuildSideEffects() throws Exception {
        for (final Integer floor : new Integer[]{null, 7, 101}) {
            final var operations = new GoDistributionOperations(new InspectOnlyArchives(floor));
            final Path output = temporary.resolve("unknown-floor-dist");
            try (var archive = new ArchiveCommand(temporary.resolve("original.jar"), null,
                    "fixture-only".toCharArray(), List.of(), List.of(), false,
                    (stage, percent) -> {
                    }, CancellationProbe.NEVER)) {
                final var settings = new LauncherCommand.BuildSettings(output, "windows-amd64",
                        temporary.resolve("missing.issuer"), "demo", null, null, List.of(), List.of());
                final var term = new LauncherCommand.LicenseTerm(Instant.now(), null);
                final var failure = assertThrows(IOException.class,
                        () -> operations.packageLauncher(new LauncherCommand(archive, settings, term)));
                assertTrue(failure.getMessage().contains("Cannot determine a supported Java version"));
                assertFalse(Files.exists(output));
            }
        }
    }

    /**
     * 仅为普通归档添加桥接参数，并按原顺序将用户参数各自保留为独立值。
     */
    @Test
    void plainArgumentsAddSystemClassLoaderWithoutSplittingSpaces() throws Exception {
        final List<String> requested = List.of("-Xmx512m", "-Dsample.path=C:\\Program Files\\Demo App",
                "--add-opens=java.base/java.lang=ALL-UNNAMED");

        assertEquals(List.of("-Xmx512m", "-Dsample.path=C:\\Program Files\\Demo App",
                        "--add-opens=java.base/java.lang=ALL-UNNAMED", PLAIN_SYSTEM_CLASS_LOADER),
                GoDistributionOperations.configuredJvmArguments("PLAIN", requested));
    }

    /**
     * 用户已设置相同属性时复用该值，使准备过程幂等且不追加重复参数。
     */
    @Test
    void plainArgumentsKeepOneMatchingSystemClassLoader() throws Exception {
        final List<String> requested = List.of("-Xmx1g", PLAIN_SYSTEM_CLASS_LOADER,
                "-Dsample.path=with spaces", PLAIN_SYSTEM_CLASS_LOADER);
        final List<String> expected = List.of("-Xmx1g", PLAIN_SYSTEM_CLASS_LOADER, "-Dsample.path=with spaces");

        assertEquals(expected, GoDistributionOperations.configuredJvmArguments("PLAIN", requested));
        assertEquals(expected, GoDistributionOperations.configuredJvmArguments("PLAIN", expected));
    }

    /**
     * 在读取发行凭据、创建暂存区或探测 Go 编译器前拒绝自定义系统类加载器。
     */
    @Test
    void plainSystemClassLoaderConflictFailsBeforeBuildSideEffects() throws Exception {
        final var operations = new GoDistributionOperations(new InspectOnlyArchives());
        final Path issuerFile = temporary.resolve("missing.issuer");
        final Path output = temporary.resolve("dist");
        final IOException emptyValueFailure = assertThrows(IOException.class,
                () -> GoDistributionOperations.configuredJvmArguments("PLAIN",
                        List.of("-Djava.system.class.loader")));
        assertTrue(emptyValueFailure.getMessage().contains("requires its XJar Plus system class loader"));
        try (var archive = new ArchiveCommand(temporary.resolve("original.jar"), null,
                "fixture-only".toCharArray(), List.of(), List.of(), false,
                (stage, percent) -> {
                }, CancellationProbe.NEVER)) {
            final var settings = new LauncherCommand.BuildSettings(output, "windows-amd64", issuerFile,
                    "demo", null, temporary.resolve("missing-go"),
                    List.of("-Djava.system.class.loader=custom.Loader"), List.of());
            final var command = new LauncherCommand(archive, settings,
                    new LauncherCommand.LicenseTerm(Instant.now(), null));

            final IOException failure = assertThrows(IOException.class, () -> operations.packageLauncher(command));

            assertTrue(failure.getMessage().contains("requires its XJar Plus system class loader"));
            assertFalse(Files.exists(output));
        }
    }

    /**
     * Boot 和 Solon 由原生启动器构造类加载器，因此不改动其参数。
     */
    @Test
    void frameworkArgumentsRemainUnchanged() throws Exception {
        final List<String> requested = List.of("-Xmx2g", "-Djava.system.class.loader=custom.Loader",
                "-Dsample.path=C:\\Program Files\\Framework App");

        for (final String kind : List.of("BOOT_CLASSIC", "BOOT_MODERN", "SOLON")) {
            assertEquals(requested, GoDistributionOperations.configuredJvmArguments(kind, requested));
        }
    }

    /**
     * 模板必须按源文件白名单打包，且不能受开发者工作区中其他文件影响。
     */
    @Test
    void templateContainsSourcesButNoCustomerPayload() throws Exception {
        final Path source = temporary.resolve("source");
        LauncherTemplate.materialize(source);
        assertTrue(Files.isRegularFile(source.resolve("go.mod")));
        assertTrue(Files.isRegularFile(source.resolve("cmd/launcher/main.go")));
        assertFalse(Files.exists(source.resolve("cmd/launcher/payload/config.json")));
        assertFalse(Files.exists(source.resolve("cmd/launcher/payload/app.jar")));
        assertFalse(Files.exists(source.resolve("cmd/launcher/payload/jdk.zip")));
        try (var files = Files.walk(source)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith("_test.go")));
        }
    }

    /**
     * 编译器启动前取消任务时，不应依赖查找或执行本机 Go 程序。
     */
    @Test
    void cancelledCompilerDoesNotStart() {
        assertThrows(CancellationException.class,
                () -> GoCompiler.check(temporary.resolve("missing-go"), temporary, () -> true));
        assertFalse(Files.exists(temporary.resolve("go-version.log")));
    }

    /**
     * 此替身只提供归档检查；凭据专属操作若意外处理归档，测试应立即失败。
     */
    private static final class InspectOnlyArchives implements ArchiveOperations {
        private final Integer minimumJava;

        /**
     * 对不检查真实字节码的凭据测试夹具使用合理的 Java 8 下限。
         */
        private InspectOnlyArchives() {
            this(8);
        }

        /**
     * 通过无效检查结果测试打包边界，无需伪造客户归档。
         */
        private InspectOnlyArchives(final Integer floor) {
            minimumJava = floor;
        }

        /**
     * 允许测试打包预检而无需从磁盘读取客户 JAR。
         */
        @Override
        public ArchiveSummary inspect(final Path path) {
            return new ArchiveSummary("PLAIN", "Example", false, 1, minimumJava);
        }

        /**
     * 若意外调用归档保护则明确失败，不返回会误导测试的占位产物。
         */
        @Override
        public BuildArtifact encrypt(final ArchiveCommand command) {
            throw new UnsupportedOperationException("No encryption expected");
        }

        /**
     * 凭据专属测试中若意外调用恢复逻辑则明确失败。
         */
        @Override
        public BuildArtifact decrypt(final ArchiveCommand command) {
            throw new UnsupportedOperationException("No decryption expected");
        }

        /**
     * 此夹具刻意不包含真实归档，因此不能声称已完成认证。
         */
        @Override
        public ArchiveSummary verify(final ArchiveCommand command) {
            throw new UnsupportedOperationException("No verification expected");
        }
    }
}
