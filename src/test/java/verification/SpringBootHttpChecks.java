package verification;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/** Shared bounded assertions keep standalone-JVM and native-launcher acceptance on the same HTTP contract. */
final class SpringBootHttpChecks {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private static final Map<String, Integer> IF_RANGE_BASELINE = new LinkedHashMap<>();
    static final List<String> STATIC_NAMES = List.of("asset.txt", "empty.bin", "block16.bin", "large.bin");

    /** Read the pristine archive as the byte authority so HTTP tests remain independent of the decryption implementation. */
    static Map<String, byte[]> resources(Path original) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(original.toFile())) {
            for (String name : STATIC_NAMES) {
                var entry = jar.getJarEntry("BOOT-INF/classes/static/" + name);
                require(entry != null, "Missing static fixture " + name);
                try (var input = jar.getInputStream(entry)) { result.put(name, input.readAllBytes()); }
            }
        }
        require(result.get("empty.bin").length == 0, "Empty fixture is not empty");
        require(result.get("block16.bin").length == 16, "Block fixture must have exactly one AES block");
        require(result.get("large.bin").length == 131089, "Large binary fixture size changed");
        return result;
    }

    /** Verify plaintext framing and reads while comparing optional If-Range semantics against the pristine framework baseline. */
    static void verify(int port, String version, Map<String, byte[]> resources, String label) throws Exception {
        HttpResponse<byte[]> probe = request(port, "/probe", "GET", Map.of());
        String expected = "boot=" + version + ";property=property-decrypted-ok;resource=resource-decrypted-ok";
        require(probe.statusCode() == 200 && text(probe).equals(expected), label + " probe mismatch: " + text(probe));
        HttpResponse<byte[]> contract = request(port, "/resource-contract", "GET", Map.of());
        require(contract.statusCode() == 200 && text(contract).equals("resource-contract-ok"), label + " connection contract: " + text(contract));
        for (var fixture : resources.entrySet()) {
            String path = "/" + fixture.getKey();
            byte[] expectedBytes = fixture.getValue();
            HttpResponse<byte[]> get = request(port, path, "GET", Map.of());
            assertBody(get, 200, expectedBytes, label + " GET " + path);
            assertLength(get, expectedBytes.length, label + " GET " + path);
            HttpResponse<byte[]> head = request(port, path, "HEAD", Map.of());
            require(head.statusCode() == 200 && head.body().length == 0, label + " HEAD body/status " + path);
            assertLength(head, expectedBytes.length, label + " HEAD " + path);
            String modified = get.headers().firstValue("Last-Modified").orElseThrow(() -> new AssertionError("Missing Last-Modified: " + path));
            assertNotModified(request(port, path, "GET", Map.of("If-Modified-Since", modified)), label + " modified " + path);
            int length = expectedBytes.length;
            if (length > 0) {
                int prefixEnd = Math.min(5, length - 1);
                assertRange(request(port, path, "GET", Map.of("Range", "bytes=0-" + prefixEnd)),
                        expectedBytes, 0, prefixEnd + 1, label + " prefix " + path);
                int suffix = Math.min(7, length);
                assertRange(request(port, path, "GET", Map.of("Range", "bytes=-" + suffix)),
                        expectedBytes, length - suffix, length, label + " suffix " + path);
                assertRange(request(port, path, "GET", Map.of("Range", "bytes=0-" + prefixEnd, "If-Range", modified)),
                        expectedBytes, 0, prefixEnd + 1, label + " matching If-Range " + path);
                HttpResponse<byte[]> staleRange = request(port, path, "GET", Map.of("Range", "bytes=0-" + prefixEnd,
                        "If-Range", "Thu, 01 Jan 1970 00:00:00 GMT"));
                String baselineKey = version + " " + path;
                Integer baseline = IF_RANGE_BASELINE.get(baselineKey);
                if (label.equals("original")) {
                    IF_RANGE_BASELINE.put(baselineKey, staleRange.statusCode());
                    if (staleRange.statusCode() == 206) System.out.println("BASELINE If-Range mismatch ignored by framework: " + baselineKey);
                } else if (baseline != null) {
                    require(staleRange.statusCode() == baseline, label + " changed baseline If-Range behavior " + path);
                }
                if (staleRange.statusCode() == 206) {
                    assertRange(staleRange, expectedBytes, 0, prefixEnd + 1, label + " framework-ignored If-Range " + path);
                    if (baseline == null && !label.equals("original")) System.out.println("FRAMEWORK If-Range mismatch ignored: " + label + " " + path);
                } else {
                    assertBody(staleRange, 200, expectedBytes, label + " stale If-Range " + path);
                    assertLength(staleRange, length, label + " stale If-Range " + path);
                }
                if (length > 13) {
                    assertMultipart(request(port, path, "GET", Map.of("Range", "bytes=0-2,13-16")),
                            expectedBytes, label + " multiple ranges " + path);
                }
            }
            HttpResponse<byte[]> invalid = request(port, path, "GET", Map.of("Range", "bytes=" + (length + 19) + "-"));
            require(invalid.statusCode() == 416, label + " out-of-range status " + path + ": " + invalid.statusCode());
            require(invalid.headers().firstValue("Content-Range").orElse("").equals("bytes */" + length), label + " out-of-range length " + path);
            String etagPath = "/etag/" + fixture.getKey();
            HttpResponse<byte[]> tagged = request(port, etagPath, "GET", Map.of());
            assertBody(tagged, 200, expectedBytes, label + " ETag GET " + path);
            assertLength(tagged, length, label + " ETag GET " + path);
            String etag = tagged.headers().firstValue("ETag").orElseThrow(() -> new AssertionError("Missing ETag: " + path));
            assertNotModified(request(port, etagPath, "GET", Map.of("If-None-Match", etag)), label + " ETag conditional " + path);
            assertBody(request(port, etagPath, "GET", Map.of("If-None-Match", "\"unmatched-fixture-tag\"")),
                    200, expectedBytes, label + " stale ETag " + path);
            System.out.println("PASS " + label + " " + path + " bytes=" + length + " GET/HEAD/Range/304/ETag"
                    + (length > 13 ? "/multipart" : "") + (length > 0 ? "/If-Range" : ""));
        }
        concurrentGets(port, resources, label);
        System.out.println("PASS " + label + " probe=" + expected + " resource-contract/getContent/metadata");
    }

    /** Check both bytes and status so a correctly sized error page cannot satisfy a resource assertion. */
    private static void assertBody(HttpResponse<byte[]> response, int status, byte[] expected, String label) {
        require(response.statusCode() == status, label + " status=" + response.statusCode());
        require(Arrays.equals(response.body(), expected), label + " body mismatch, received=" + response.body().length);
    }

    /** Enforce plaintext framing explicitly because decrypting a stream while advertising ciphertext size caused the original regression. */
    private static void assertLength(HttpResponse<byte[]> response, long length, String label) {
        require(response.headers().firstValueAsLong("Content-Length").orElse(-1) == length, label + " Content-Length=" + response.headers().firstValue("Content-Length"));
    }

    /** Range bodies and total-size metadata must describe the plaintext coordinate system, including suffix requests. */
    private static void assertRange(HttpResponse<byte[]> response, byte[] source, int start, int end, String label) {
        assertBody(response, 206, Arrays.copyOfRange(source, start, end), label);
        assertLength(response, end - start, label);
        require(response.headers().firstValue("Content-Range").orElse("").equals("bytes " + start + "-" + (end - 1) + "/" + source.length), label + " Content-Range");
    }

    /** Parse MIME framing without trimming payload bytes so multipart ranges cannot pass after corrupting binary data or boundary placement. */
    private static void assertMultipart(HttpResponse<byte[]> response, byte[] source, String label) {
        require(response.statusCode() == 206, label + " status=" + response.statusCode());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        require(contentType.toLowerCase(Locale.ROOT).startsWith("multipart/byteranges;"), label + " Content-Type=" + contentType);
        var boundaryMatch = Pattern.compile("(?:^|;)\\s*boundary=(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE).matcher(contentType);
        require(boundaryMatch.find(), label + " missing MIME boundary");
        String boundary = boundaryMatch.group(1) != null ? boundaryMatch.group(1) : boundaryMatch.group(2);
        require(!boundary.isEmpty(), label + " empty MIME boundary");
        String wire = new String(response.body(), StandardCharsets.ISO_8859_1);
        String[] sections = wire.split(Pattern.quote("--" + boundary), -1);
        require(sections.length == 4, label + " expected exactly two MIME parts, sections=" + sections.length);
        require(sections[0].isEmpty() || sections[0].equals("\r\n"), label + " unexpected MIME preamble");
        require(sections[3].equals("--") || sections[3].equals("--\r\n"), label + " missing terminal MIME boundary");
        int[][] positions = {{0, 3}, {13, Math.min(17, source.length)}};
        for (int index = 0; index < positions.length; index++) {
            String section = sections[index + 1];
            require(section.startsWith("\r\n") && section.endsWith("\r\n"), label + " malformed part framing " + index);
            String part = section.substring(2, section.length() - 2);
            int headerEnd = part.indexOf("\r\n\r\n");
            require(headerEnd >= 0, label + " missing part header boundary " + index);
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line : part.substring(0, headerEnd).split("\r\n")) {
                int colon = line.indexOf(':');
                require(colon > 0, label + " malformed part header " + line);
                headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
            }
            int start = positions[index][0];
            int end = positions[index][1];
            String range = "bytes " + start + "-" + (end - 1) + "/" + source.length;
            require(range.equals(headers.get("content-range")), label + " part Content-Range " + index + ": " + headers);
            byte[] bytes = part.substring(headerEnd + 4).getBytes(StandardCharsets.ISO_8859_1);
            require(Arrays.equals(bytes, Arrays.copyOfRange(source, start, end)), label + " part bytes " + index);
        }
        response.headers().firstValueAsLong("Content-Length").ifPresent(length ->
                require(length == response.body().length, label + " multipart Content-Length mismatch"));
    }

    /** Conditional responses must omit the body; checking only status would overlook accidental ciphertext emission. */
    private static void assertNotModified(HttpResponse<byte[]> response, String label) {
        require(response.statusCode() == 304 && response.body().length == 0, label + " expected empty 304, got " + response.statusCode());
    }

    /** Concurrent repeated reads expose shared-cipher or consumed-stream bugs while one aggregate deadline bounds the group. */
    private static void concurrentGets(int port, Map<String, byte[]> resources, String label) throws Exception {
        List<CompletableFuture<HttpResponse<byte[]>>> futures = new ArrayList<>();
        List<byte[]> expected = new ArrayList<>();
        try {
            for (int repeat = 0; repeat < 2; repeat++) {
                for (var fixture : resources.entrySet()) {
                    futures.add(HTTP.sendAsync(builder(port, "/" + fixture.getKey(), "GET", Map.of()).build(), HttpResponse.BodyHandlers.ofByteArray()));
                    expected.add(fixture.getValue());
                }
            }
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            for (int index = 0; index < futures.size(); index++) {
                HttpResponse<byte[]> response = futures.get(index).getNow(null);
                assertBody(response, 200, expected.get(index), label + " concurrent " + index);
                assertLength(response, expected.get(index).length, label + " concurrent " + index);
            }
        } finally {
            futures.forEach(future -> future.cancel(true));
        }
        System.out.println("PASS " + label + " concurrent GET count=" + futures.size());
    }

    /** Cancel stalled body subscriptions explicitly because request timeouts alone do not always bound response-body completion. */
    static HttpResponse<byte[]> request(int port, String route, String method, Map<String, String> headers) throws Exception {
        var future = HTTP.sendAsync(builder(port, route, method, headers).build(), HttpResponse.BodyHandlers.ofByteArray());
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new IOException("HTTP body timeout: " + method + " " + route, timeout);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            throw failed;
        } finally {
            future.cancel(true);
        }
    }

    /** Use identical request timeouts and header construction in sequential and concurrent probes. */
    private static HttpRequest.Builder builder(int port, String route, String method, Map<String, String> headers) {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + route))
                .timeout(Duration.ofSeconds(4)).method(method, HttpRequest.BodyPublishers.noBody());
        headers.forEach(request::header);
        return request;
    }

    /** Wait on a controller before testing resources and retain owned handles while a launcher can still identify its children. */
    static void awaitReady(Process process, Map<Long, ProcessHandle> owned, int port, int seconds, Path log) throws Exception {
        awaitGet(process, owned, port, "/probe", seconds, log);
    }

    /** Bound startup retries and surface captured output if a JVM or launcher fails before becoming ready. */
    static String awaitGet(Process process, Map<Long, ProcessHandle> owned, int port, String route, int seconds, Path log) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            capture(process, owned);
            require(process.isAlive(), "Process exited before " + route + ": " + logText(log));
            try {
                var response = request(port, route, "GET", Map.of());
                if (response.statusCode() == 200) return text(response);
            } catch (IOException initializing) {
                // Runtime extraction and framework initialization temporarily refuse HTTP requests.
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Readiness timeout: " + route + ": " + logText(log));
    }

    /** Avoid ephemeral client ports so a later closure probe cannot confuse a reused client endpoint with a surviving listener. */
    static int freePort() throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            int port = ThreadLocalRandom.current().nextInt(20000, 40000);
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress("127.0.0.1", port));
                return port;
            } catch (java.net.BindException occupied) {
                // Probe another candidate without disturbing existing local listeners.
            }
        }
        throw new IOException("No free verification port");
    }

    /** Retain only descendants of this test process so cleanup never scans for or terminates unrelated Java applications. */
    static void capture(Process process, Map<Long, ProcessHandle> owned) {
        process.descendants().forEach(handle -> owned.putIfAbsent(handle.pid(), handle));
    }

    /** Stop the owned process tree and verify listeners disappear even when an earlier assertion failed. */
    static void stop(Process process, Map<Long, ProcessHandle> owned, int... ports) throws Exception {
        capture(process, owned);
        if (process.isAlive()) process.destroyForcibly();
        for (var child : owned.values()) if (child.isAlive()) child.destroyForcibly();
        require(process.waitFor(10, TimeUnit.SECONDS), "Parent process did not stop");
        for (var child : owned.values()) child.onExit().get(10, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && Arrays.stream(ports).anyMatch(SpringBootHttpChecks::reachable)) Thread.sleep(100);
        require(owned.values().stream().noneMatch(ProcessHandle::isAlive), "Owned child survived cleanup");
        for (int port : ports) require(!reachable(port), "Listener survived cleanup: " + port);
        System.out.println("PASS process cleanup; ports closed=" + Arrays.toString(ports));
    }

    /** A refused TCP connection proves the listener closed rather than merely returning an HTTP error. */
    private static boolean reachable(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 250);
            return true;
        } catch (IOException closed) {
            return false;
        }
    }

    /** Replacement decoding keeps ASCII exception causes readable even when Windows emitted legacy console bytes. */
    static String logText(Path log) throws IOException {
        return new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
    }

    /** Decode only textual endpoints; binary resources stay byte arrays throughout the assertions. */
    static String text(HttpResponse<byte[]> response) {
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    /** Unconditional assertions also work when callers use Java source-file mode without -ea. */
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
