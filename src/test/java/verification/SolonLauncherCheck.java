package verification;

import io.xjar.XCryptos;
import io.xjar.XGo;
import io.xjar.XKit;
import io.xjar.utils.Platform;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Opt-in Windows distribution test; every artifact and runtime belongs to a fresh supplied output directory. */
public class SolonLauncherCheck {
    private static final String PASSWORD = "solon-distribution-test-only";
    private static final String CODE = UUID.randomUUID().toString();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    /** Exercise the public packaging API and then distribute only the binary and license, without developer runtimes on PATH. */
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && args[0].equals("--resume-run")) {
            Path root = Path.of(args[1]).toAbsolutePath();
            run(root, root.resolve("分发目录 with spaces"), "resumed-after-bind-failure", Integer.parseInt(args[2]), true, true, false);
            return;
        }
        if (args.length != 4) throw new IllegalArgumentException("Expected <demo.jar> <jdk.zip> <go-bin> <output-parent>");
        Path root = Files.createTempDirectory(Path.of(args[3]).toAbsolutePath(), "distribution-");
        Path build = root.resolve("build");
        Path dist = Files.createDirectories(root.resolve("分发目录 with spaces"));
        int appPort = freePort();
        XCryptos.encryption().inputJar(args[0]).password(PASSWORD).jdkZip(args[1]).goPath(args[2])
                .platform(Platform.WINDOWS_AMD64).include("**").jarArgs("-XX:+DisableAttachMechanism -Dserver.port=" + appPort)
                .code(CODE).validStartDate(date(-3600)).validEndDate(date(3600)).output(build.toString()).ok();
        Files.copy(build.resolve("main.exe"), dist.resolve("main.exe"));
        Files.copy(build.resolve("key.x"), dist.resolve("key.x"));
        System.out.println("DISTRIBUTION=" + dist);
        try (var files = Files.list(dist)) {
            System.out.println("DISTRIBUTED_FILES=" + files.map(p -> p.getFileName().toString()).toList());
        }
        run(root, dist, "default-license", appPort, true, true, false);

        int overridePort = freePort();
        license(dist, CODE, PASSWORD, date(-3600), date(3600), "-Dserver.port=" + overridePort + " -XshowSettings:properties -Ddemo.label=\"hello solon\"");
        run(root, dist, "renewed-license-args", overridePort, true, true, false);

        Files.move(dist.resolve("key.x"), root.resolve("saved-valid-key.x"));
        run(root, dist, "missing-license-fallback", appPort, false, true, false);
        license(dist, "another-application", PASSWORD, date(-3600), date(3600), "");
        run(root, dist, "wrong-code-fallback", appPort, false, true, false);
        license(dist, CODE, "wrong-encryption-password", date(-3600), date(3600), "");
        run(root, dist, "wrong-password-fallback", appPort, false, true, false);

        license(dist, CODE, PASSWORD, date(-3600), date(-60), "");
        run(root, dist, "expired-at-startup", appPort, true, false, false);
        license(dist, CODE, PASSWORD, date(600), date(3600), "");
        rejectFuture(root, dist);

        license(dist, CODE, PASSWORD, date(-3600), date(18), "");
        run(root, dist, "expires-while-running", appPort, true, true, true);
        license(dist, CODE, PASSWORD, date(-3600), date(3600), "");
        run(root, dist, "renew-after-expiry", appPort, true, true, false);
        System.out.println("ALL DISTRIBUTION CHECKS PASSED; OUTPUT=" + root);
    }

    /** License replacement is limited to the isolated test distribution; embedded credentials remain fixed across renewals. */
    private static void license(Path dist, String code, String password, String start, String end, String arguments) throws Exception {
        XGo.license(dist.toString(), XKit.key(password), code, arguments, start, end);
    }

    /** Keep relative times in the host timezone because the launcher parses its validity dates in local time. */
    private static String date(int seconds) { return LocalDateTime.now().plusSeconds(seconds).format(DATE); }

    /** Use ports below Windows' default ephemeral range so HTTP client connections cannot reuse a stopped server's port. */
    private static int freePort() throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            int port = java.util.concurrent.ThreadLocalRandom.current().nextInt(20000, 40000);
            try (var socket = new ServerSocket(port)) { return socket.getLocalPort(); }
            catch (java.net.BindException occupied) { }
        }
        throw new IllegalStateException("No free test server port");
    }

    /** Isolate the launcher's destructive runtime refresh and prove it does not discover Java or Go through PATH. */
    private static Process start(Path root, Path dist, String label, int statusPort) throws Exception {
        Path runtime = Files.createDirectories(root.resolve("runtime"));
        ProcessBuilder builder = new ProcessBuilder(dist.resolve("main.exe").toString())
                .directory(root.toFile()).redirectErrorStream(true).redirectOutput(root.resolve(label + ".log").toFile());
        builder.environment().put("PATH", System.getenv("SystemRoot") + "\\System32");
        builder.environment().remove("JAVA_HOME");
        builder.environment().remove("GOROOT");
        builder.environment().put("TEMP", runtime.toString());
        builder.environment().put("TMP", runtime.toString());
        builder.environment().put("XJAR_LICENSE_HTTP_ADDR", "127.0.0.1:" + statusPort);
        return builder.start();
    }

    /** Verify public behavior and ownership, preserving the status service when only the application expires. */
    private static void run(Path root, Path dist, String label, int appPort, boolean available, boolean valid, boolean expires) throws Exception {
        int statusPort = freePort();
        Process process = start(root, dist, label, statusPort);
        List<ProcessHandle> owned = new ArrayList<>();
        try {
            String rawStatus = awaitGet(process, statusPort, "/xjp/license/status", 30);
            String status = rawStatus.replaceAll("\\s+", "");
            require(status.contains("\"available\":" + available), label + " availability: " + status);
            require(status.contains("\"valid\":" + valid), label + " validity: " + status);
            require(!status.contains(CODE) && !status.contains(PASSWORD) && !status.contains("demo.label"), "Status leaks private fields");
            require(request(statusPort, "/xjp/healthz", "GET").statusCode() == 200, "Health check failed");
            require(request(statusPort, "/xjp/license/status", "HEAD").statusCode() == 200, "HEAD failed");
            require(request(statusPort, "/xjp/license/status", "POST").statusCode() == 405, "POST not rejected");
            if (valid) {
                require(awaitGet(process, appPort, "/hello?name=solon", 40).equals("Hello solon!"), "Controller response mismatch");
                require(request(appPort, "/hello", "GET").body().equals("Hello world!"), "Default parameter mismatch");
                require(request(appPort, "/hello2?name=solon", "GET").body().contains("Hello solon!"), "Template failed");
                require(request(appPort, "/base.css", "GET").body().equals("div{font-size: 1.5em;}"), "Static resource failed");
                owned.addAll(process.descendants().toList());
                ProcessHandle java = owned.stream().filter(p -> p.info().command().orElse("").endsWith("java.exe")).findFirst().orElseThrow();
                String command = java.info().command().orElseThrow();
                require(Path.of(command).startsWith(root.resolve("runtime")), "Java did not use embedded runtime: " + command);
                if (label.equals("renewed-license-args")) {
                    // Windows ProcessHandle does not expose arguments; query the JVM's own property output instead.
                    require(Files.readString(root.resolve(label + ".log")).contains("demo.label = hello solon"), "Quoted JVM argument split incorrectly");
                }
                if (expires) {
                    long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                    while (java.isAlive() && System.nanoTime() < deadline) Thread.sleep(250);
                    require(!java.isAlive(), "Application survived expiry");
                    var end = Pattern.compile("\"validEndDate\"\\s*:\\s*\"([^\"]+)\"").matcher(rawStatus);
                    require(end.find(), "Missing expiration time");
                    System.out.println("EXPIRY_STOP_DELAY_MS=" + Duration.between(LocalDateTime.parse(end.group(1), DATE), LocalDateTime.now()).toMillis());
                    require(process.isAlive(), "Status service exited on expiry");
                    require(request(statusPort, "/xjp/license/status", "GET").body().replaceAll("\\s+", "").contains("\"valid\":false"), "Status did not expire");
                    require(request(statusPort, "/xjp/healthz", "GET").statusCode() == 200, "Expired status service unhealthy");
                    require(!reachable(appPort), "Application port still reachable after expiry");
                }
                System.out.println(label + " EMBEDDED_JAVA=" + command);
            } else {
                Thread.sleep(500);
                // Windows may create a console host even when the launcher correctly skips Java.
                require(process.descendants().noneMatch(p -> p.info().command().orElse("").endsWith("java.exe")), "Expired application started Java");
                require(!reachable(appPort), "Expired application is reachable");
            }
            System.out.println("PASS " + label + " STATUS=" + status);
        } finally {
            owned.addAll(process.descendants().toList());
            stop(process, owned);
        }
    }

    /** Future-dated licenses fail before opening either service, unlike already-expired licenses. */
    private static void rejectFuture(Path root, Path dist) throws Exception {
        Process process = start(root, dist, "future-license", freePort());
        try {
            require(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() != 0, "Future license did not fail");
            require(Files.readString(root.resolve("future-license.log")).contains("E_VALIDITY"), "Unexpected future-license failure");
            System.out.println("PASS future-license");
        } finally { stop(process, process.descendants().toList()); }
    }

    /** Poll only until readiness or a bounded timeout, surfacing premature child exits as failures. */
    private static String awaitGet(Process process, int port, String route, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) throw new AssertionError("Launcher exited before ready: " + process.exitValue());
            try {
                var response = request(port, route, "GET");
                if (response.statusCode() == 200) return response.body();
            } catch (java.io.IOException ignored) { }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out: " + port + route);
    }

    /** Bound network calls so an unhealthy service cannot stall the validation workflow. */
    private static HttpResponse<String> request(int port, String path, String method) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(2)).method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Connection refusal, rather than just HTTP failure, proves the business listener has stopped. */
    private static boolean reachable(int port) {
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress("127.0.0.1", port), 300); return true; }
        catch (java.io.IOException expected) { return false; }
    }

    /** Restrict cleanup to handles created by this test, including Java children that Windows would otherwise orphan. */
    private static void stop(Process process, List<ProcessHandle> children) throws Exception {
        for (ProcessHandle child : children) if (child.isAlive()) child.destroyForcibly();
        if (process.isAlive()) process.destroyForcibly();
        require(process.waitFor(10, TimeUnit.SECONDS), "Launcher cleanup timed out");
        for (ProcessHandle child : children) child.onExit().get(10, TimeUnit.SECONDS);
    }

    /** Use unconditional checks because the source launcher does not require Java assertions to be enabled. */
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
