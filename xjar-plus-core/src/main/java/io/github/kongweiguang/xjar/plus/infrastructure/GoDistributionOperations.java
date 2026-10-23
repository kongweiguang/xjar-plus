/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

import io.github.kongweiguang.xjar.plus.domain.model.ArchiveCommand;
import io.github.kongweiguang.xjar.plus.domain.model.BuildArtifact;
import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;
import io.github.kongweiguang.xjar.plus.domain.model.LauncherCommand;
import io.github.kongweiguang.xjar.plus.domain.model.LicenseIssueCommand;
import io.github.kongweiguang.xjar.plus.domain.port.ArchiveOperations;
import io.github.kongweiguang.xjar.plus.domain.port.DistributionOperations;
import io.github.kongweiguang.xjar.plus.format.IssuerCredentials;
import io.github.kongweiguang.xjar.plus.format.KeyEnvelope;
import io.github.kongweiguang.xjar.plus.format.LicenseCodec;
import io.github.kongweiguang.xjar.plus.infrastructure.distribution.GoCompiler;
import io.github.kongweiguang.xjar.plus.infrastructure.distribution.JdkArchiveValidator;
import io.github.kongweiguang.xjar.plus.infrastructure.distribution.LauncherTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.json.JsonFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarFile;

/**
 * 协调私有凭据、归档保护和 Go 编译器；许可证策略由 Go 侧执行。
 */
public final class GoDistributionOperations implements DistributionOperations {
    private static final JsonFactory JSON_FACTORY = JsonFactory.builder().build();
    private static final long MAX_BUNDLE_BYTES = 20L * 1024 * 1024 * 1024;
    private static final String SYSTEM_CLASS_LOADER_PROPERTY = "-Djava.system.class.loader";
    private static final String PLAIN_SYSTEM_CLASS_LOADER =
            SYSTEM_CLASS_LOADER_PROPERTY
            + "=io.github.kongweiguang.xjar.plus.runtime.common.XJarPlusSystemClassLoader";
    private final ArchiveOperations archives;

    /**
     * 复用归档端口，避免独立加密与授权打包演变成两套加密实现。
     */
    public GoDistributionOperations(final ArchiveOperations archiveOperations) {
        archives = Objects.requireNonNull(archiveOperations, "archiveOperations");
    }

    /**
     * 仅在私有构建区解包密钥；密码和数据密钥都不会作为命令行参数传递。
     */
    private static void prepareConfiguration(final LauncherCommand command, final IssuerCredentials issuer,
                                             final BuildArtifact encrypted, final Path payload,
                                             final int minimumJava, final List<String> jvmArguments)
            throws IOException {
        final char[] password = command.archive().password();
        try {
            final byte[] key = unwrapDataKey(encrypted.output(), password);
            final byte[] licenseKey = issuer.getLicenseKey();
            try {
                writeConfiguration(payload.resolve("config.json"), command, encrypted.artifactId(),
                        key, licenseKey, minimumJava, jvmArguments);
            } finally {
                Arrays.fill(key, (byte) 0);
                Arrays.fill(licenseKey, (byte) 0);
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 将固定的 Go 配置契约直接写入私有目录，避免发行凭据进入最终产物。
     */
    private static void writeConfiguration(final Path output, final LauncherCommand command, final String artifactId,
                                           final byte[] dataKey, final byte[] licenseKey, final int minimumJava,
                                           final List<String> jvmArguments) throws IOException {
        try (JsonGenerator writer = JSON_FACTORY.createGenerator(Files.newBufferedWriter(output,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW))) {
            writer.writeStartObject();
            writer.writeNumberProperty("version", 2);
            writer.writeStringProperty("appId", command.appId());
            writer.writeStringProperty("artifactId", artifactId);
            writer.writeStringProperty("dataKey", Base64.getEncoder().encodeToString(dataKey));
            writer.writeStringProperty("licenseKey", Base64.getEncoder().encodeToString(licenseKey));
            writer.writeBooleanProperty("embeddedJdk", command.jdkZip() != null);
            writer.writeNumberProperty("minimumJava", minimumJava);
            writeArguments(writer, "jvmArgs", jvmArguments);
            writeArguments(writer, "appArgs", command.appArgs());
            writer.writeEndObject();
        } catch (JacksonException failure) {
            throw serializationFailure(failure);
        }
        PrivateOutputTransaction.makePrivate(output);
    }

    /**
     * 在读取凭据或调用编译器前确定普通归档的加载器要求，并原样保留框架参数。
     */
    static List<String> configuredJvmArguments(final String archiveKind, final List<String> requested)
            throws IOException {
        if (!ArchiveFamily.PLAIN.displayName().equals(archiveKind)) {
            return requested;
        }
        final List<String> configured = new ArrayList<>(requested.size() + 1);
        boolean loaderPresent = false;
        for (final String argument : requested) {
            if (!isSystemClassLoaderArgument(argument)) {
                configured.add(argument);
                continue;
            }
            if (!PLAIN_SYSTEM_CLASS_LOADER.equals(argument)) {
                throw new IOException("PLAIN launcher requires its XJar Plus system class loader; "
                                      + "remove the conflicting JVM argument");
            }
            if (!loaderPresent) {
                configured.add(argument);
                loaderPresent = true;
            }
        }
        if (!loaderPresent) {
            configured.add(PLAIN_SYSTEM_CLASS_LOADER);
        }
        return List.copyOf(configured);
    }

    /**
     * 只匹配精确的 JVM 属性，避免改动名称相似的应用参数。
     */
    private static boolean isSystemClassLoaderArgument(final String argument) {
        return argument.equals(SYSTEM_CLASS_LOADER_PROPERTY)
               || argument.startsWith(SYSTEM_CLASS_LOADER_PROPERTY + "=");
    }

    /**
     * 将参数逐项写入 JSON 数组；不做 shell 引号处理，确保空格和参数边界原样保留。
     */
    private static void writeArguments(final JsonGenerator writer, final String name, final List<String> arguments)
            throws IOException {
        writer.writeArrayPropertyStart(name);
        for (final String argument : arguments) {
            if (argument.indexOf('\0') >= 0) {
                throw new IOException("JVM and application arguments cannot contain NUL characters");
            }
            writer.writeString(argument);
        }
        writer.writeEndArray();
    }

    /**
     * Jackson 将底层写入异常包装为运行时异常；恢复 IOException 语义并保留关闭期间的次级错误。
     */
    private static IOException serializationFailure(final JacksonException failure) {
        if (failure.getCause() instanceof IOException ioFailure) {
            for (final Throwable suppressed : failure.getSuppressed()) {
                ioFailure.addSuppressed(suppressed);
            }
            return ioFailure;
        }
        final IOException converted = new IOException("Cannot serialize launcher configuration", failure);
        for (final Throwable suppressed : failure.getSuppressed()) {
            converted.addSuppressed(suppressed);
        }
        return converted;
    }

    /**
     * 解析发行凭据前先限制读取长度，避免损坏文件或错误路径导致无界内存分配。
     */
    private static IssuerCredentials readIssuer(final Path path) throws IOException {
        final byte[] encoded;
        try (InputStream input = Files.newInputStream(path)) {
            encoded = input.readNBytes(297);
        }
        try {
            return IssuerCredentials.parse(encoded);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid issuer credential; use the original v2 issuer file", failure);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    /**
     * 只读取小型密钥信封，不会将解密后的业务归档写入文件系统。
     */
    private static byte[] unwrapDataKey(final Path jarPath, final char[] password) throws IOException {
        try (var jar = new JarFile(jarPath.toFile())) {
            final var entry = jar.getJarEntry("META-INF/xjar-plus/envelope.bin");
            if (entry == null) {
                throw new IOException("Protected archive is missing its key envelope");
            }
            try (InputStream input = jar.getInputStream(entry)) {
                try (var envelope = KeyEnvelope.parse(input.readNBytes(4097))) {
                    return envelope.unwrap(password);
                }
            }
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            throw new IOException("Cannot authenticate the protected archive's data key", failure);
        }
    }

    /**
     * 用稳定摘要生成目录名，避免把任意 Unicode appId 当成路径或平台设备名。
     */
    private static String packageDirectory(final String appId) throws IOException {
        try {
            return "app-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(appId.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) {
            throw new IOException("SHA-256 is unavailable", failure);
        }
    }

    /**
     * 转换密码提供方错误时不在诊断信息中包含任何密钥字节。
     */
    private static byte[] issue(final IssuerCredentials issuer, final Instant notBefore, final Instant notAfter)
            throws IOException {
        try {
            return LicenseCodec.issue(issuer, notBefore, notAfter);
        } catch (GeneralSecurityException failure) {
            throw new IOException("Cannot create the authenticated license", failure);
        }
    }

    /**
     * 若编译期间许可证已过期则拒绝发布，避免生成即刻不可用的发行包。
     */
    private static void checkExpiration(final Instant notAfter) throws IOException {
        if (notAfter != null && Instant.now().getEpochSecond() >= notAfter.getEpochSecond()) {
            throw new IOException("License expired during or before packaging; choose a later expiration");
        }
    }

    /**
     * 在私有编译前以有界内存复制大型 JDK，并提供取消检查和明确的大小上限。
     */
    private static void copyBundle(final Path input, final Path output, final CancellationProbe cancellation)
            throws IOException {
        if (!Files.isRegularFile(input) || Files.size(input) > MAX_BUNDLE_BYTES) {
            throw new IOException("JDK bundle is not a regular file within the 20 GiB size limit");
        }
        try (var source = Files.newInputStream(input);
             var destination = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW)) {
            final byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                cancellation.check();
                copied += count;
                if (copied > MAX_BUNDLE_BYTES) {
                    throw new IOException("JDK bundle grew beyond the 20 GiB size limit while copying");
                }
                destination.write(buffer, 0, count);
            }
        }
    }

    /**
     * 仅创建一次发行凭据，设置仅所有者可读权限并拒绝覆盖已有文件。
     */
    @Override
    public BuildArtifact createIssuer(final Path output, final String appId) throws IOException {
        try (var issuer = IssuerCredentials.create(appId)) {
            final byte[] encoded = issuer.encode();
            try (var transaction = new PrivateOutputTransaction(output, false)) {
                Files.write(transaction.stagedOutput(), encoded, StandardOpenOption.CREATE_NEW);
                PrivateOutputTransaction.makePrivate(transaction.stagedOutput());
                return new BuildArtifact(transaction.commit(), null,
                        List.of("Keep this issuer credential private and backed up"));
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    /**
     * 使用持久保存的发行凭据签发新许可证，与应用构建密码相互独立。
     */
    @Override
    public BuildArtifact issueLicense(final LicenseIssueCommand command) throws IOException {
        try (var issuer = readIssuer(command.issuerFile())) {
            final byte[] encoded = issue(issuer, command.notBefore(), command.notAfter());
            try (var transaction = new PrivateOutputTransaction(command.output(), false)) {
                Files.write(transaction.stagedOutput(), encoded, StandardOpenOption.CREATE_NEW);
                return new BuildArtifact(transaction.commit(), null, List.of());
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    /**
     * 在读取凭据或调用编译器前要求明确最低 Java 版本，且只发布验证通过的发行包。
     */
    @Override
    // ArchiveCommand 由 CoreApplication 持有并关闭；此处仅借用。
    @SuppressWarnings("PMD.CloseResource")
    public BuildArtifact packageLauncher(final LauncherCommand command) throws IOException {
        final ArchiveCommand archive = command.archive();
        archive.cancellation().check();
        final var summary = archives.inspect(archive.input());
        if (summary.encrypted()) {
            throw new IOException("Package from the original unencrypted JAR, not an already protected artifact");
        }
        final Integer minimumJava = summary.minimumJava();
        if (minimumJava == null || minimumJava < 8 || minimumJava > 100) {
            throw new IOException("Cannot determine a supported Java version for the original application");
        }
        final List<String> jvmArguments = configuredJvmArguments(summary.kind(), command.jvmArgs());
        try (var issuer = readIssuer(command.issuerFile())) {
            return packageWithIssuer(command, issuer, minimumJava, jvmArguments);
        }
    }

    /**
     * 让发行凭据覆盖完整构建过程，包括目标预检和所有失败路径。
     */
    // 该方法借用应用层持有的命令，不能在打包结束前提前关闭。
    @SuppressWarnings("PMD.CloseResource")
    private BuildArtifact packageWithIssuer(final LauncherCommand command, final IssuerCredentials issuer,
                                            final int minimumJava, final List<String> jvmArguments) throws IOException {
        final ArchiveCommand archive = command.archive();
        if (!issuer.getAppId().equals(command.appId())) {
            throw new IOException("Issuer credential belongs to a different appId");
        }
        checkExpiration(command.notAfter());
        final Path destination = command.output().toAbsolutePath().normalize()
                .resolve(packageDirectory(command.appId()));
        try (var transaction = new PrivateOutputTransaction(destination, true)) {
            archive.progress().report("Checking target and Go compiler", 2);
            GoCompiler.check(command.goExecutable(), transaction.workspace(), archive.cancellation());
            if (command.jdkZip() != null) {
                JdkArchiveValidator.validate(command.jdkZip(), command.target(), minimumJava);
            }
            final Path source = transaction.workspace().resolve("launcher-source");
            LauncherTemplate.materialize(source);
            final Path payload = source.resolve("cmd/launcher/payload");
            Files.createDirectories(payload);
            final BuildArtifact encrypted = encryptPayload(archive, payload.resolve("app.jar"));
            if (command.jdkZip() != null) {
                archive.progress().report("Copying target JDK", 62);
                copyBundle(command.jdkZip(), payload.resolve("jdk.zip"), archive.cancellation());
                JdkArchiveValidator.validate(payload.resolve("jdk.zip"), command.target(), minimumJava);
            }
            prepareConfiguration(command, issuer, encrypted, payload, minimumJava, jvmArguments);
            Files.write(transaction.stagedOutput().resolve("key.x"),
                    issue(issuer, command.notBefore(), command.notAfter()), StandardOpenOption.CREATE_NEW);
            final String binaryName = command.target().startsWith("windows-") ? "launcher.exe" : "launcher";
            archive.progress().report("Compiling Go launcher", 72);
            GoCompiler.compile(command.goExecutable(), source, command.target(),
                    transaction.stagedOutput().resolve(binaryName), archive.cancellation());
            archive.cancellation().check();
            checkExpiration(command.notAfter());
            archive.progress().report("Committing verified distribution", 98);
            final Path committed = transaction.commit();
            archive.progress().report("Complete", 100);
            return new BuildArtifact(committed.resolve(binaryName), encrypted.artifactId(), encrypted.warnings());
        }
    }

    /**
     * 将归档进度映射到打包的首个阶段，避免过早报告整个发行包已完成。
     */
    private BuildArtifact encryptPayload(final ArchiveCommand original, final Path output) throws IOException {
        final char[] password = original.password();
        try (var command = new ArchiveCommand(original.input(), output, password,
                original.includes(), original.excludes(), original.encryptDependencies(),
                (stage, percent) -> original.progress().report(stage, 5 + percent * 55 / 100),
                original.cancellation())) {
            final BuildArtifact result = archives.encrypt(command);
            try (var verification = new ArchiveCommand(result.output(), null, password,
                    List.of(), List.of(), false, command.progress(), command.cancellation())) {
                archives.verify(verification);
            }
            return result;
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
