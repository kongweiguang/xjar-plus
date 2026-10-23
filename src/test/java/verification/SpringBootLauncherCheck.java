package verification;

import io.xjar.XCryptos;
import io.xjar.utils.Platform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import static verification.SpringBootHttpChecks.*;

/** Opt-in Windows distribution test: only main.exe and key.x are copied into the isolated distribution directory. */
public class SpringBootLauncherCheck {
    private static final String PASSWORD = "boot4-launcher-verification-only";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Prove the public packaging flow embeds ciphertext before reusing HTTP checks against its isolated JDK distribution. */
    public static void main(String[] args) throws Exception {
        require(args.length == 5, "Expected <app.jar> <Windows jdk.zip> <go-bin-dir> <output-dir> <expected-boot-version>");
        Path app = Path.of(args[0]).toAbsolutePath().normalize();
        Path jdk = Path.of(args[1]).toAbsolutePath().normalize();
        Path go = Path.of(args[2]).toAbsolutePath().normalize();
        Path output = Files.createDirectories(Path.of(args[3]).toAbsolutePath().normalize());
        Path build = Files.createTempDirectory(output, "build-");
        Path distribution = Files.createTempDirectory(output, "分发 中文 with spaces-");
        Path runtime = Files.createDirectories(output.resolve("runtime"));
        Path log = output.resolve("launcher-" + UUID.randomUUID() + ".log");
        Map<String, byte[]> resources = resources(app);
        int appPort = freePort();
        int statusPort;
        do { statusPort = freePort(); } while (statusPort == appPort);
        String code = "spring-boot-verification-" + UUID.randomUUID();
        XCryptos.encryption().inputJar(app.toString()).password(PASSWORD)
                .jdkZip(jdk.toString()).goPath(go.toString()).platform(Platform.WINDOWS_AMD64)
                .include("**").jarArgs("-Dserver.address=127.0.0.1 -Dserver.port=" + appPort)
                .code(code).validStartDate(date(-3600)).validEndDate(date(3600))
                .output(build.toString()).ok();
        verifyEmbeddedArchive(app, embeddedArchive(build), args[4]);
        Files.copy(build.resolve("main.exe"), distribution.resolve("main.exe"));
        Files.copy(build.resolve("key.x"), distribution.resolve("key.x"));
        try (var files = Files.list(distribution)) {
            require(files.count() == 2, "Distribution must contain only main.exe and key.x");
        }
        System.out.println("OUTPUT=" + output + " ENCRYPTION=all-entries");
        System.out.println("DISTRIBUTION=" + distribution + " LOG=" + log);
        Process process = start(distribution, runtime, log, statusPort);
        Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
        Throwable failure = null;
        try {
            String status = awaitGet(process, owned, statusPort, "/xjp/license/status", 45, log);
            String compact = status.replaceAll("\\s+", "");
            require(compact.contains("\"available\":true") && compact.contains("\"valid\":true"), "Default license not valid: " + status);
            require(!status.contains(PASSWORD) && !status.contains(code), "Status leaked private values");
            require(awaitGet(process, owned, statusPort, "/xjp/healthz", 10, log).equals("ok"), "Health response mismatch");
            awaitReady(process, owned, appPort, 60, log);
            verify(appPort, args[4], resources, "native-distribution");
            capture(process, owned);
            ProcessHandle java = owned.values().stream().filter(handle -> handle.isAlive()
                            && handle.info().command().map(command -> Path.of(command).getFileName().toString()
                            .equalsIgnoreCase("java.exe")).orElse(false))
                    .findFirst().orElseThrow(() -> new AssertionError("No owned java.exe process"));
            Path command = Path.of(java.info().command().orElseThrow()).toAbsolutePath().normalize();
            require(command.startsWith(runtime), "Java did not come from isolated embedded runtime: " + command);
            System.out.println("EMBEDDED_JAVA_PID=" + java.pid() + " EMBEDDED_JAVA=" + command);
            System.out.println("PASS license-and-health=" + compact);
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            try { stop(process, owned, appPort, statusPort); }
            catch (Exception | Error cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
        System.out.println("ALL SPRING BOOT " + args[4] + " DISTRIBUTION CHECKS PASSED; OUTPUT=" + output);
    }

    /** Read the generated Go binding so assertions inspect the exact application JAR selected by this build's embed contract. */
    private static Path embeddedArchive(Path build) throws IOException {
        String source = Files.readString(build.resolve("main.go"));
        var jarPath = Pattern.compile("(?m)^\\s*jarPath\\s*=\\s*\"(resource/[^\"\\r\\n]+)\"\\s*$").matcher(source);
        require(jarPath.find(), "Missing generated launcher application resource binding");
        require(source.contains("//go:embed resource/*"), "Generated launcher does not embed its resource directory");
        Path embedded = build.resolve(jarPath.group(1)).normalize();
        require(embedded.startsWith(build) && Files.isRegularFile(embedded), "Missing embedded application JAR: " + embedded);
        return embedded;
    }

    /** A working HTTP response could come from a plaintext package, so inspect payload bytes before executing the native distribution. */
    private static void verifyEmbeddedArchive(Path original, Path embedded, String version) throws Exception {
        try (JarFile plain = new JarFile(original.toFile()); JarFile cipher = new JarFile(embedded.toFile())) {
            require(version.equals(plain.getManifest().getMainAttributes().getValue("Spring-Boot-Version")), "Unexpected original Boot version");
            require(version.equals(cipher.getManifest().getMainAttributes().getValue("Spring-Boot-Version")), "Embedded Boot version changed");
            require("io.xjar.boot.XJarLauncher".equals(cipher.getManifest().getMainAttributes().getValue("Main-Class")), "Embedded JAR is not using the encrypted launcher");
            String originalLauncher = plain.getManifest().getMainAttributes().getValue("Main-Class");
            require(originalLauncher != null && originalLauncher.equals(cipher.getManifest().getMainAttributes().getValue("Boot-Main-Class")), "Original embedded launcher not retained");
            require(cipher.getJarEntry("BOOT-INF/classes/XJAR-INF/INDEXES.IDX") != null, "Missing embedded encryption index");
            for (String name : STATIC_NAMES) {
                String entry = "BOOT-INF/classes/static/" + name;
                byte[] ciphertext = entryBytes(cipher, entry);
                require(ciphertext.length > 0 && !Arrays.equals(entryBytes(plain, entry), ciphertext), "Embedded static resource is not ciphertext: " + name);
            }
            var applicationClasses = plain.stream().filter(entry -> !entry.isDirectory()
                    && entry.getName().startsWith("BOOT-INF/classes/") && entry.getName().endsWith(".class")).toList();
            require(!applicationClasses.isEmpty(), "Missing original application classes");
            for (var entry : applicationClasses) {
                require(!Arrays.equals(entryBytes(plain, entry.getName()), entryBytes(cipher, entry.getName())), "Embedded application class is not ciphertext: " + entry.getName());
            }
            String library = "BOOT-INF/lib/spring-boot-" + version + ".jar";
            String dependency = "org/springframework/boot/SpringBootVersion.class";
            require(!Arrays.equals(nestedBytes(plain, library, dependency), nestedBytes(cipher, library, dependency)), "Embedded Spring Boot dependency class is not ciphertext");
            var loader = plain.stream().filter(entry -> !entry.isDirectory()
                    && entry.getName().startsWith("org/springframework/boot/loader/")).toList();
            require(!loader.isEmpty(), "Missing original Boot loader entries");
            for (var entry : loader) {
                require(Arrays.equals(entryBytes(plain, entry.getName()), entryBytes(cipher, entry.getName())), "Embedded original Boot loader was replaced: " + entry.getName());
            }
            var dependencies = cipher.stream().filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".jar")).toList();
            require(!dependencies.isEmpty(), "Missing embedded nested dependencies");
            for (var dependencyEntry : dependencies) {
                require(dependencyEntry.getMethod() == JarEntry.STORED, "Embedded nested dependency compressed: " + dependencyEntry.getName());
            }
            System.out.println("PASS native embedded archive=" + embedded + " actual ciphertext static=" + STATIC_NAMES.size()
                    + " application-classes=" + applicationClasses.size() + " dependency=" + dependency
                    + " retained-loader=" + loader.size() + " nested-JARs=STORED");
        }
    }

    /** Close physical entry streams promptly because the same archive will later be extracted and executed on Windows. */
    private static byte[] entryBytes(JarFile jar, String name) throws IOException {
        JarEntry entry = jar.getJarEntry(name);
        require(entry != null, "Missing archive entry: " + name);
        try (var input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    /** Compare the nested class itself because changed outer JAR metadata cannot establish that dependency encryption occurred. */
    private static byte[] nestedBytes(JarFile jar, String library, String name) throws IOException {
        JarEntry archive = jar.getJarEntry(library);
        require(archive != null, "Missing nested dependency: " + library);
        try (var input = new ZipInputStream(jar.getInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.getName().equals(name)) return input.readAllBytes();
            }
        }
        throw new AssertionError("Missing nested resource: " + name);
    }

    /** License dates use the host timezone because the generated Go launcher parses these strings as local wall-clock times. */
    private static String date(int seconds) {
        return LocalDateTime.now().plusSeconds(seconds).format(DATE);
    }

    /** Constrain runtime extraction and remove developer discovery paths before launch so bundled Java is the only usable JVM. */
    private static Process start(Path distribution, Path runtime, Path log, int statusPort) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(distribution.resolve("main.exe").toString())
                .directory(distribution.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        String windows = System.getenv("SystemRoot");
        require(windows != null && !windows.isBlank(), "SystemRoot is required for Windows distribution verification");
        builder.environment().put("PATH", Path.of(windows, "System32").toString());
        builder.environment().remove("JAVA_HOME");
        builder.environment().remove("GOROOT");
        builder.environment().put("TEMP", runtime.toString());
        builder.environment().put("TMP", runtime.toString());
        builder.environment().put("XJAR_LICENSE_HTTP_ADDR", "127.0.0.1:" + statusPort);
        return builder.start();
    }
}
