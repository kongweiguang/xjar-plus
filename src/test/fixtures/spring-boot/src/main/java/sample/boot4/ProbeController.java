package sample.boot4;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootVersion;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProbeController {
    private final String configuredValue;

    /** Constructor injection makes encrypted application.properties part of the observable response instead of an unused fixture. */
    public ProbeController(@Value("${probe.value}") String configuredValue) {
        this.configuredValue = configuredValue;
    }

    /** Read through the context class loader on every request to exercise the runtime decryption path for ordinary resources. */
    @GetMapping(value = "/probe", produces = "text/plain;charset=UTF-8")
    public String probe() throws IOException {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream input = loader.getResourceAsStream("sample/data.txt")) {
            if (input == null) {
                throw new IOException("Missing sample/data.txt");
            }
            String data = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            return "boot=" + SpringBootVersion.getVersion()
                    + ";property=" + configuredValue + ";resource=" + data;
        }
    }

    /** Exercise connection APIs directly because correct HTTP output alone can miss metadata or getContent decryption regressions. */
    @GetMapping(value = "/resource-contract", produces = "text/plain;charset=UTF-8")
    public String resourceContract() throws IOException {
        for (String name : List.of("asset.txt", "empty.bin", "block16.bin", "large.bin")) {
            byte[] expected = expected(name);
            URL url = Thread.currentThread().getContextClassLoader().getResource("static/" + name);
            check(url != null, "Missing resource " + name);
            URLConnection metadata = url.openConnection();
            check(metadata instanceof JarURLConnection, "Expected JAR connection for " + name);
            check(metadata.getContentLength() == expected.length, "getContentLength " + name);
            check(metadata.getContentLengthLong() == expected.length, "getContentLengthLong " + name);
            check(((JarURLConnection) metadata).getJarEntry().getSize() == expected.length, "getJarEntry size " + name);
            try (InputStream input = url.openConnection().getInputStream()) {
                check(Arrays.equals(input.readAllBytes(), expected), "getInputStream " + name);
            }
            checkContent(url.openConnection().getContent(), expected, "getContent " + name);
            checkContent(url.openConnection().getContent(new Class<?>[]{InputStream.class}), expected, "getContent(types) " + name);
        }
        return "resource-contract-ok";
    }

    /** Own returned streams so API-contract probes cannot leak open archive handles into later HTTP and cleanup checks. */
    private static void checkContent(Object content, byte[] expected, String label) throws IOException {
        check(content instanceof InputStream, label + " did not return an InputStream");
        try (InputStream input = (InputStream) content) {
            check(Arrays.equals(input.readAllBytes(), expected), label + " returned ciphertext");
        }
    }

    /** Deterministic expected bytes prevent the test from accepting ciphertext merely because two broken read paths agree. */
    private static byte[] expected(String name) {
        return switch (name) {
            case "asset.txt" -> "static-decrypted-ok\n".getBytes(StandardCharsets.UTF_8);
            case "empty.bin" -> new byte[0];
            case "block16.bin" -> "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
            case "large.bin" -> {
                byte[] bytes = new byte[131089];
                for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) (index * 31 + 7);
                yield bytes;
            }
            default -> throw new IllegalArgumentException("Unknown fixture " + name);
        };
    }

    /** Fail through the controller response so standalone JVM and embedded-launcher tests share the same observable assertions. */
    private static void check(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
