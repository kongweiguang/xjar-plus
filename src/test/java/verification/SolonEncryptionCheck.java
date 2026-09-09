package verification;

import io.xjar.XCryptos;
import io.xjar.XFilters;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.jar.*;

/** Windows integration check for the Solon 4.1.0 web demo; intentionally opt-in because it launches HTTP servers. */
public class SolonEncryptionCheck {
    private static final String PASSWORD = "solon-local-verification-only";

    /** Compare identical HTTP behavior and inspect ciphertext so a successful plaintext launch cannot masquerade as encryption support. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected: <Solon 4.1.0 demo.jar> <existing output directory>");
        Path original = Path.of(args[0]).toAbsolutePath();
        Path root = Files.createTempDirectory(Path.of(args[1]).toAbsolutePath(), "solon-");
        System.out.println("OUTPUT=" + root);
        try (JarFile jar = new JarFile(original.toFile())) {
            System.out.println("ORIGINAL_MANIFEST=" + jar.getManifest().getMainAttributes().entrySet());
            System.out.println("NESTED_JARS=" + jar.stream().filter(e -> e.getName().endsWith(".jar")).count());
        }
        Map<String,String> baseline = probe(original, root, "original", false);
        for (String label : List.of("classes-only", "classes-and-resources", "including-dependencies", "all-entries")) {
            boolean resources = !label.equals("classes-only");
            Path encrypted = root.resolve(label + ".jar");
            var filter = XFilters.any().mix(XFilters.ant("com/example/demo/**"));
            if (label.equals("including-dependencies")) filter.mix(XFilters.ant("org/noear/solon/core/**"));
            if (label.equals("all-entries")) filter.mix(XFilters.ant("**"));
            if (resources) {
                filter.mix(XFilters.ant("app.yml"));
                filter.mix(XFilters.ant("templates/**"));
                filter.mix(XFilters.ant("static/**"));
            }
            XCryptos.encrypt(original.toFile(), encrypted.toFile(), PASSWORD, filter);
            try (JarFile plain = new JarFile(original.toFile()); JarFile cipher = new JarFile(encrypted.toFile())) {
                var indexEntry = cipher.getJarEntry("BOOT-INF/classes/XJAR-INF/INDEXES.IDX");
                if (indexEntry == null) throw new AssertionError("Missing encryption index");
                for (var entry : cipher.stream().filter(e -> e.getName().endsWith(".jar")).toList()) {
                    if (entry.getMethod() != JarEntry.STORED) throw new AssertionError("Compressed dependency: " + entry);
                }
                String indexes = new String(cipher.getInputStream(indexEntry).readAllBytes());
                System.out.println(label + " INDEXES=" + indexes.replace('\n', ';'));
                for (String entry : indexes.lines().toList()) {
                    byte[] before = plain.getInputStream(plain.getJarEntry("BOOT-INF/classes/" + entry)).readAllBytes();
                    byte[] after = cipher.getInputStream(cipher.getJarEntry("BOOT-INF/classes/" + entry)).readAllBytes();
                    if (Arrays.equals(before, after)) throw new AssertionError("Not encrypted: " + entry);
                }
            }
            String library = "BOOT-INF/lib/solon-4.1.0.jar";
            String libraryClass = "org/noear/solon/core/Props.class";
            if (label.equals("including-dependencies") || label.equals("all-entries")) {
                if (Arrays.equals(nestedEntry(original, library, libraryClass), nestedEntry(encrypted, library, libraryClass)))
                    throw new AssertionError("Nested dependency was not encrypted");
            }
            Map<String,String> actual = probe(encrypted, root, label, true);
            if (!baseline.equals(actual)) throw new AssertionError("Response mismatch: " + actual);
            Path restored = root.resolve(label + "-restored.jar");
            XCryptos.decrypt(encrypted.toFile(), restored.toFile(), PASSWORD, filter);
            try (JarFile plain = new JarFile(original.toFile()); JarFile decoded = new JarFile(restored.toFile())) {
                if (!plain.getManifest().getMainAttributes().equals(decoded.getManifest().getMainAttributes()))
                    throw new AssertionError("Manifest not restored");
                for (String entry : List.of("com/example/demo/App.class", "com/example/demo/DemoController.class",
                        "app.yml", "templates/hello2.ftl", "static/base.css")) {
                    String path = "BOOT-INF/classes/" + entry;
                    if (!Arrays.equals(plain.getInputStream(plain.getJarEntry(path)).readAllBytes(),
                            decoded.getInputStream(decoded.getJarEntry(path)).readAllBytes()))
                        throw new AssertionError("Restore mismatch: " + path);
                }
            }
            if (!Arrays.equals(nestedEntry(original, library, libraryClass), nestedEntry(restored, library, libraryClass)))
                throw new AssertionError("Nested dependency not restored");
            if (!baseline.equals(probe(restored, root, label + "-restored", false)))
                throw new AssertionError("Restored application mismatch");
            System.out.println("PASS " + label);
            rejectWrongPassword(encrypted, root, label);
        }
    }

    /** Compare bytes inside nested libraries because outer ZIP differences alone do not prove class encryption. */
    private static byte[] nestedEntry(Path archive, String library, String resource) throws Exception {
        try (JarFile jar = new JarFile(archive.toFile());
             var nested = new java.util.zip.ZipInputStream(jar.getInputStream(jar.getJarEntry(library)))) {
            java.util.zip.ZipEntry entry;
            while ((entry = nested.getNextEntry()) != null) {
                if (entry.getName().equals(resource)) return nested.readAllBytes();
            }
            throw new AssertionError("Missing nested resource: " + resource);
        }
    }

    /** A ciphertext check alone does not prove runtime key enforcement; the same archive must reject another key. */
    private static void rejectWrongPassword(Path jar, Path root, String label) throws Exception {
        Path log = root.resolve(label + "-wrong-password.log");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dserver.port=0", "-jar", jar.toString()).directory(root.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            process.getOutputStream().write("AES/CBC/PKCS5Padding\n128\n128\nwrong-key\n".getBytes());
            process.getOutputStream().close();
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() == 0)
                throw new AssertionError("Wrong password not rejected: " + label);
            if (!Files.readString(log).contains("BadPaddingException"))
                throw new AssertionError("Unexpected password failure: " + Files.readString(log));
            System.out.println("PASS wrong-password " + label);
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    /** Own each child process and use a temporary free port to avoid disturbing existing application instances. */
    private static Map<String,String> probe(Path jar, Path root, String label, boolean encrypted) throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        Path log = root.resolve(label + ".log");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-Dserver.port=" + port, "-jar", jar.toString());
        builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        try {
            if (encrypted) {
                process.getOutputStream().write(("AES/CBC/PKCS5Padding\n128\n128\n" + PASSWORD + "\n").getBytes());
                process.getOutputStream().flush();
            }
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            Map<String,String> responses = new LinkedHashMap<>();
            for (String route : List.of("/hello", "/hello?name=solon", "/hello2?name=solon", "/base.css")) {
                HttpResponse<String> response = null;
                for (int retry = 0; retry < 80; retry++) {
                    if (!process.isAlive()) throw new AssertionError(label + " exited: " + Files.readString(log));
                    try {
                        response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route))
                                .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                        break;
                    } catch (java.io.IOException ex) { Thread.sleep(250); }
                }
                if (response == null || response.statusCode() != 200) throw new AssertionError(label + " HTTP failed: " + route + " " + response);
                responses.put(route, response.body());
                System.out.println(label + " " + route + " HTTP=" + response.statusCode() + " BODY=" + response.body().replace('\n', ' '));
            }
            return responses;
        } finally {
            process.destroy();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly().waitFor();
        }
    }
}
