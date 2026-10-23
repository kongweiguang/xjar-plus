package verification;

import io.xjar.XCryptos;
import io.xjar.XFilters;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipInputStream;
import static verification.SpringBootHttpChecks.*;

/** Opt-in real Spring Boot executable-JAR check; build src/test/fixtures/spring-boot separately before invoking it. */
public class SpringBootEncryptionCheck {
    private static final String PASSWORD = "boot4-verification-only";

    /** Keep target Boot loaders in their own child JVMs so the tool's compile-time Boot dependency cannot fake compatibility. */
    public static void main(String[] args) throws Exception {
        require(args.length == 2 || args.length == 3, "Expected <Spring Boot fixture app.jar> <output-parent> [expected-version]");
        Path original = Path.of(args[0]).toAbsolutePath().normalize();
        String version;
        List<String> applicationEntries;
        try (JarFile jar = new JarFile(original.toFile())) {
            version = jar.getManifest().getMainAttributes().getValue("Spring-Boot-Version");
            require(version != null, "Input is not a repackaged Spring Boot executable JAR");
            require(args.length < 3 || args[2].equals(version), "Unexpected fixture Boot version: " + version);
            applicationEntries = jar.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith("BOOT-INF/classes/"))
                    .map(JarEntry::getName).toList();
        }
        Path parent = Files.createDirectories(Path.of(args[1]).toAbsolutePath().normalize());
        Path root = Files.createTempDirectory(parent, "spring-boot-" + version + "-");
        Map<String, byte[]> resources = resources(original);
        String library = "BOOT-INF/lib/spring-boot-" + version + ".jar";
        String dependency = "org/springframework/boot/SpringBootVersion.class";
        System.out.println("OUTPUT=" + root + " VERSION=" + version + " JAVA=" + System.getProperty("java.version"));
        probe(original, root, "original", false, version, resources);
        int failures = 0;
        for (String label : List.of("classes-only", "classes-and-resources", "including-dependencies", "all-entries")) {
            try {
                var filter = XFilters.any().mix(XFilters.ant("sample/boot4/**"));
                if (!label.equals("classes-only")) {
                    filter.mix(XFilters.ant("application.properties")).mix(XFilters.ant("sample/data.txt")).mix(XFilters.ant("static/**"));
                }
                boolean nested = label.equals("including-dependencies") || label.equals("all-entries");
                if (nested) filter.mix(XFilters.ant(dependency));
                if (label.equals("all-entries")) filter.mix(XFilters.ant("**"));
                Path encrypted = root.resolve(label + ".jar");
                Path restored = root.resolve(label + "-restored.jar");
                XCryptos.encrypt(original.toFile(), encrypted.toFile(), PASSWORD, filter);
                verifyArchive(original, encrypted, applicationEntries, label);
                require(Arrays.equals(nestedBytes(original, library, dependency), nestedBytes(encrypted, library, dependency)) != nested,
                        "Nested dependency encryption coverage mismatch: " + label);
                probe(encrypted, root, label, true, version, resources);
                rejectWrongPassword(encrypted, root, label);
                XCryptos.decrypt(encrypted.toFile(), restored.toFile(), PASSWORD, filter);
                verifyRestored(original, restored, applicationEntries);
                require(Arrays.equals(nestedBytes(original, library, dependency), nestedBytes(restored, library, dependency)), "Dependency not restored: " + label);
                probe(restored, root, label + "-restored", false, version, resources);
                System.out.println("PASS " + label + " archive/encrypt/runtime/wrong-password/decrypt/runtime");
            } catch (Exception | AssertionError failure) {
                failures++;
                System.out.println("FAIL " + label + " " + failure);
                failure.printStackTrace(System.out);
            }
        }
        require(failures == 0, failures + " encryption mode(s) failed; OUTPUT=" + root);
        System.out.println("ALL SPRING BOOT " + version + " ENCRYPTION CHECKS PASSED; OUTPUT=" + root);
    }

    /** Prove selected bytes changed while untouched entries, original loader classes and nested storage retain their contracts. */
    private static void verifyArchive(Path original, Path encrypted, List<String> applicationEntries, String label) throws Exception {
        try (JarFile plain = new JarFile(original.toFile()); JarFile cipher = new JarFile(encrypted.toFile())) {
            require("io.xjar.boot.XJarLauncher".equals(cipher.getManifest().getMainAttributes().getValue("Main-Class")), "Wrong launcher");
            require(plain.getManifest().getMainAttributes().getValue("Main-Class").equals(cipher.getManifest().getMainAttributes().getValue("Boot-Main-Class")), "Original launcher not retained");
            require(cipher.getJarEntry("BOOT-INF/classes/XJAR-INF/INDEXES.IDX") != null, "Missing encryption index");
            for (String entry : applicationEntries) {
                boolean selected = entry.endsWith(".class") || !label.equals("classes-only");
                require(Arrays.equals(bytes(plain, entry), bytes(cipher, entry)) != selected, "Encryption coverage mismatch: " + entry);
            }
            for (var entry : cipher.stream().filter(entry -> entry.getName().endsWith(".jar")).toList()) {
                require(entry.getMethod() == JarEntry.STORED, "Compressed nested dependency: " + entry);
            }
            List<String> loader = plain.stream().filter(entry -> !entry.isDirectory() && entry.getName().startsWith("org/springframework/boot/loader/"))
                    .map(JarEntry::getName).toList();
            require(!loader.isEmpty(), "No original Boot loader entries");
            for (String name : loader) require(Arrays.equals(bytes(plain, name), bytes(cipher, name)), "Original Boot loader was replaced: " + name);
            System.out.println("PASS " + label + " actual ciphertext; nested JARs STORED; retained loader entries=" + loader.size());
        }
    }

    /** Compare application and every original dependency entry while ignoring ZIP container metadata and additional injected tool entries. */
    private static void verifyRestored(Path original, Path restored, List<String> applicationEntries) throws Exception {
        try (JarFile plain = new JarFile(original.toFile()); JarFile decoded = new JarFile(restored.toFile())) {
            require(plain.getManifest().getMainAttributes().equals(decoded.getManifest().getMainAttributes()), "Manifest not restored");
            for (String entry : applicationEntries) require(Arrays.equals(bytes(plain, entry), bytes(decoded, entry)), "Restore mismatch: " + entry);
            require(decoded.getJarEntry("BOOT-INF/classes/XJAR-INF/INDEXES.IDX") == null, "Encryption index survived restore");
            int libraries = 0;
            long logicalEntries = 0;
            for (var entry : plain.stream().filter(entry -> !entry.isDirectory()
                    && entry.getName().startsWith("BOOT-INF/lib/") && entry.getName().endsWith(".jar")).toList()) {
                logicalEntries += verifyRestoredLibrary(plain, decoded, entry.getName());
                libraries++;
            }
            for (var entry : decoded.stream().filter(entry -> entry.getName().endsWith(".jar")).toList()) {
                require(entry.getMethod() == JarEntry.STORED, "Restored nested JAR compressed: " + entry);
            }
            System.out.println("PASS restored plaintext: application entries=" + applicationEntries.size()
                    + "; dependency JARs=" + libraries + "; original dependency logical entries=" + logicalEntries);
        }
    }

    /** Keep nested files byte-exact except manifest attributes: the existing Manifest.write path legitimately changes folding and attribute order. */
    private static int verifyRestoredLibrary(JarFile plain, JarFile decoded, String library) throws Exception {
        Map<String, byte[]> originals = new LinkedHashMap<>();
        try (var input = new ZipInputStream(plain.getInputStream(Objects.requireNonNull(plain.getJarEntry(library), library)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                require(originals.putIfAbsent(entry.getName(), input.readAllBytes()) == null,
                        "Duplicate original dependency entry: " + library + "!/" + entry.getName());
            }
        }
        int count = originals.size();
        require(decoded.getJarEntry(library) != null, "Restored dependency missing: " + library);
        try (var input = new ZipInputStream(decoded.getInputStream(decoded.getJarEntry(library)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (entry.isDirectory() || !originals.containsKey(entry.getName())) continue;
                byte[] expected = originals.remove(entry.getName());
                byte[] actual = input.readAllBytes();
                if (entry.getName().equals("META-INF/MANIFEST.MF")) {
                    var expectedManifest = new java.util.jar.Manifest(new java.io.ByteArrayInputStream(expected));
                    var actualManifest = new java.util.jar.Manifest(new java.io.ByteArrayInputStream(actual));
                    require(expectedManifest.equals(actualManifest), "Restored dependency manifest attributes differ: " + library);
                    if (!Arrays.equals(expected, actual)) System.out.println("PASS normalized dependency manifest attributes: " + library);
                } else {
                    require(Arrays.equals(expected, actual), "Restored dependency entry differs: " + library + "!/" + entry.getName());
                }
            }
        }
        require(originals.isEmpty(), "Original dependency entries missing after restore: " + library + " " + originals.keySet());
        return count;
    }

    /** Close every archive entry stream immediately to keep Windows artifact handles out of later cleanup and rebuilds. */
    private static byte[] bytes(JarFile jar, String entry) throws Exception {
        try (var input = jar.getInputStream(Objects.requireNonNull(jar.getJarEntry(entry), entry))) { return input.readAllBytes(); }
    }

    /** Compare bytes inside the nested dependency because outer JAR rewrites alone do not prove dependency class encryption. */
    private static byte[] nestedBytes(Path archive, String library, String name) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile());
             var input = new ZipInputStream(jar.getInputStream(Objects.requireNonNull(jar.getJarEntry(library), library)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) if (entry.getName().equals(name)) return input.readAllBytes();
        }
        throw new AssertionError("Missing nested resource: " + name);
    }

    /** Each application owns a random loopback listener and is stopped in finally even if HTTP or connection assertions fail. */
    private static void probe(Path jar, Path root, String label, boolean encrypted, String version, Map<String, byte[]> resources) throws Exception {
        int port = freePort();
        Path log = root.resolve(label + ".log");
        Process process = new ProcessBuilder(javaExecutable(), "-Dserver.address=127.0.0.1", "-Dserver.port=" + port, "-jar", jar.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
        Throwable failure = null;
        try {
            if (encrypted) process.getOutputStream().write(("AES/CBC/PKCS5Padding\n128\n128\n" + PASSWORD + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            awaitReady(process, owned, port, 45, log);
            verify(port, version, resources, label);
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            try { stop(process, owned, port); }
            catch (Exception | Error cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    /** Require the cryptographic cause so an unrelated startup error cannot falsely count as rejection of the wrong key. */
    private static void rejectWrongPassword(Path jar, Path root, String label) throws Exception {
        int port = freePort();
        Path log = root.resolve(label + "-wrong-password.log");
        Process process = new ProcessBuilder(javaExecutable(), "-Dserver.address=127.0.0.1", "-Dserver.port=" + port, "-jar", jar.toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Map<Long, ProcessHandle> owned = new LinkedHashMap<>();
        Throwable failure = null;
        try {
            process.getOutputStream().write("AES/CBC/PKCS5Padding\n128\n128\nwrong-key\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().close();
            capture(process, owned);
            require(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() != 0, "Wrong password not rejected: " + label);
            require(logText(log).contains("BadPaddingException"), "Unexpected wrong-password failure: " + logText(log));
            System.out.println("PASS wrong-password " + label);
        } catch (Exception | Error error) {
            failure = error;
            throw error;
        } finally {
            try { stop(process, owned, port); }
            catch (Exception | Error cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    /** Reuse the invoking JDK explicitly instead of letting PATH select a different Java runtime for the application. */
    private static String javaExecutable() {
        String command = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", command).toString();
    }
}
