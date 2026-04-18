package ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.xjar.XCryptos;
import io.xjar.XEncryption;
import io.xjar.XGo;
import io.xjar.XKit;
import io.xjar.key.XKey;
import io.xjar.utils.Platform;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

public final class XJarPlusUi {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String PAGE_RESOURCE = "/ui/xjar-plus-ui.html";
    private static final Path PAGE_FILE = Path.of("src", "test", "resources", "ui", "xjar-plus-ui.html");
    private static final Map<String, JobStatus> JOBS = new ConcurrentHashMap<>();
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool();

    private XJarPlusUi() {
    }

    public static void main(String[] args) throws Exception {
        int port = readPort(args);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", XJarPlusUi::handleIndex);
        server.createContext("/api/choose", XJarPlusUi::handleChoose);
        server.createContext("/api/encrypt", XJarPlusUi::handleEncrypt);
        server.createContext("/api/license", XJarPlusUi::handleLicense);
        server.createContext("/api/status", XJarPlusUi::handleStatus);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        System.out.println("xjar-plus 界面已启动: " + url);
        System.out.println("关闭窗口或按 Ctrl+C 可停止服务。");
    }

    private static int readPort(String[] args) {
        if (args == null) {
            return 9876;
        }
        for (String arg : args) {
            if (arg != null && arg.startsWith("--port=")) {
                try {
                    return Integer.parseInt(arg.substring("--port=".length()));
                } catch (NumberFormatException ignored) {
                    return 9876;
                }
            }
        }
        return 9876;
    }

    private static void handleIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        send(exchange, 200, "text/html; charset=utf-8", page());
    }

    private static void handleEncrypt(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        Map<String, String> form = readForm(exchange);
        EncryptionForm input;
        try {
            input = EncryptionForm.from(form);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "application/json; charset=utf-8", "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            return;
        }
        String id = UUID.randomUUID().toString();
        JobStatus status = JobStatus.running("加密打包任务已提交");
        JOBS.put(id, status);

        WORKERS.submit(() -> {
            try {
                XEncryption encryption = XCryptos.encryption()
                        .inputJar(input.inputJar())
                        .password(input.password())
                        .jarArgs(input.jarArgs())
                        .jdkZip(input.jdkZip())
                        .goPath(input.goPath())
                        .platform(input.platform())
                        .validStartDate(input.validStartDate())
                        .validEndDate(input.validEndDate())
                        .code(input.code())
                        .output(input.output());
                for (String include : input.includes()) {
                    encryption.include(include);
                }
                for (String exclude : input.excludes()) {
                    encryption.exclude(exclude);
                }
                encryption.ok();
                status.complete("加密打包完成，输出目录：" + input.output());
            } catch (Exception e) {
                status.fail(rootMessage(e));
            }
        });

        send(exchange, 202, "application/json; charset=utf-8", "{\"id\":\"" + escapeJson(id) + "\"}");
    }

    private static void handleLicense(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        Map<String, String> form = readForm(exchange);
        LicenseForm input;
        try {
            input = LicenseForm.from(form);
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "application/json; charset=utf-8", "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
            return;
        }
        String id = UUID.randomUUID().toString();
        JobStatus status = JobStatus.running("license 任务已提交");
        JOBS.put(id, status);

        WORKERS.submit(() -> {
            try {
                XKey key = XKit.key(input.password());
                XGo.license(input.output(), key, input.code(), input.jarArgs(), input.validStartDate(), input.validEndDate());
                status.complete("license 生成完成，输出目录：" + input.output());
            } catch (Exception e) {
                status.fail(rootMessage(e));
            }
        });

        send(exchange, 202, "application/json; charset=utf-8", "{\"id\":\"" + escapeJson(id) + "\"}");
    }

    private static void handleChoose(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        String type = queryParams(exchange).getOrDefault("type", "file");
        try {
            String path = choosePath(type);
            send(exchange, 200, "application/json; charset=utf-8", "{\"path\":\"" + escapeJson(path) + "\"}");
        } catch (Exception e) {
            send(exchange, 500, "application/json; charset=utf-8", "{\"error\":\"" + escapeJson(rootMessage(e)) + "\"}");
        }
    }

    private static void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            send(exchange, 405, "application/json; charset=utf-8", "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        String id = queryParams(exchange).get("id");
        JobStatus status = JOBS.get(id);
        if (status == null) {
            send(exchange, 404, "application/json; charset=utf-8", "{\"state\":\"ERROR\",\"message\":\"任务不存在\"}");
            return;
        }
        send(exchange, 200, "application/json; charset=utf-8", status.toJson());
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseParams(body);
    }

    private static Map<String, String> queryParams(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return parseParams(query == null ? "" : query);
    }

    private static Map<String, String> parseParams(String value) {
        Map<String, String> params = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return params;
        }
        for (String pair : value.split("&")) {
            int index = pair.indexOf('=');
            String key = index >= 0 ? pair.substring(0, index) : pair;
            String raw = index >= 0 ? pair.substring(index + 1) : "";
            params.put(decode(key), decode(raw));
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static List<String> lines(String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String line : value.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static String required(Map<String, String> form, String name) {
        String value = form.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.trim();
    }

    private static String optional(Map<String, String> form, String name) {
        String value = form.get(name);
        return value == null ? "" : value.trim();
    }

    private static String pathWithSuffix(Map<String, String> form, String name, String suffix) {
        String value = required(form, name);
        if (!value.toLowerCase(Locale.ROOT).endsWith(suffix)) {
            throw new IllegalArgumentException(name + " 必须填写 " + suffix + " 文件路径");
        }
        return value;
    }

    private static String date(Map<String, String> form, String name) {
        String value = required(form, name);
        try {
            return LocalDateTime.parse(value, DATE_TIME_FORMATTER).format(DATE_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value).format(DATE_TIME_FORMATTER);
            } catch (DateTimeParseException ignored) {
                throw new IllegalArgumentException(name + " 必须选择日期和时间");
            }
        }
    }

    private static Platform platform(Map<String, String> form) {
        String value = required(form, "platform").toUpperCase(Locale.ROOT);
        try {
            return Platform.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("platform 不支持：" + value);
        }
    }

    private static void ensureDateRange(String start, String end) {
        LocalDateTime startDate = LocalDateTime.parse(start, DATE_TIME_FORMATTER);
        LocalDateTime endDate = LocalDateTime.parse(end, DATE_TIME_FORMATTER);
        if (!endDate.isAfter(startDate)) {
            throw new IllegalArgumentException("validEndDate 必须晚于 validStartDate");
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getName() : message;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static String page() {
        StringBuilder platforms = new StringBuilder();
        for (Platform platform : Platform.values()) {
            platforms.append("<option value=\"")
                    .append(platform.name())
                    .append("\">")
                    .append(platform.name())
                    .append(" · ")
                    .append(platform.goos())
                    .append("/")
                    .append(platform.goarch())
                    .append("</option>");
        }
        return readPageTemplate().replace("{{PLATFORM_OPTIONS}}", platforms);
    }

    private static String readPageTemplate() {
        try (InputStream in = XJarPlusUi.class.getResourceAsStream(PAGE_RESOURCE)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return Files.readString(PAGE_FILE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String choosePath(String type) throws Exception {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("当前环境不支持图形文件选择窗口，请直接粘贴路径");
        }
        AtomicReference<String> result = new AtomicReference<>("");
        AtomicReference<Exception> error = new AtomicReference<>();
        Runnable task = () -> {
            JFrame owner = new JFrame();
            try {
                owner.setUndecorated(true);
                owner.setAlwaysOnTop(true);
                owner.setSize(1, 1);
                owner.setLocationRelativeTo(null);
                owner.setVisible(true);
                owner.toFront();
                owner.requestFocus();

                JFileChooser chooser = new JFileChooser();
                chooser.setFileSelectionMode("dir".equalsIgnoreCase(type)
                        ? JFileChooser.DIRECTORIES_ONLY
                        : JFileChooser.FILES_ONLY);
                chooser.setDialogTitle("dir".equalsIgnoreCase(type) ? "选择文件夹" : "选择文件");
                int state = chooser.showOpenDialog(owner);
                if (state == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
                    result.set(chooser.getSelectedFile().getAbsolutePath());
                }
            } catch (Exception e) {
                error.set(e);
            } finally {
                owner.dispose();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeAndWait(task);
        }
        if (error.get() != null) {
            throw error.get();
        }
        return result.get();
    }

    private record EncryptionForm(String inputJar,
                                  String password,
                                  String jarArgs,
                                  String jdkZip,
                                  String goPath,
                                  Platform platform,
                                  String validStartDate,
                                  String validEndDate,
                                  String code,
                                  String output,
        List<String> includes,
                                  List<String> excludes) {
        static EncryptionForm from(Map<String, String> form) {
            String start = date(form, "validStartDate");
            String end = date(form, "validEndDate");
            ensureDateRange(start, end);
            return new EncryptionForm(
                    pathWithSuffix(form, "inputJar", ".jar"),
                    required(form, "password"),
                    optional(form, "jarArgs"),
                    pathWithSuffix(form, "jdkZip", ".zip"),
                    required(form, "goPath"),
                    XJarPlusUi.platform(form),
                    start,
                    end,
                    required(form, "code"),
                    required(form, "output"),
                    lines(form.get("includes")),
                    lines(form.get("excludes"))
            );
        }
    }

    private record LicenseForm(String output,
                               String password,
                               String code,
                               String jarArgs,
                               String validStartDate,
                               String validEndDate) {
        static LicenseForm from(Map<String, String> form) {
            String start = date(form, "validStartDate");
            String end = date(form, "validEndDate");
            ensureDateRange(start, end);
            return new LicenseForm(
                    required(form, "output"),
                    required(form, "password"),
                    required(form, "code"),
                    optional(form, "jarArgs"),
                    start,
                    end
            );
        }
    }

    private static final class JobStatus {
        private volatile String state;
        private volatile String message;

        private JobStatus(String state, String message) {
            this.state = state;
            this.message = message;
        }

        static JobStatus running(String message) {
            return new JobStatus("RUNNING", message);
        }

        void complete(String message) {
            this.state = "DONE";
            this.message = message;
        }

        void fail(String message) {
            this.state = "ERROR";
            this.message = message;
        }

        String toJson() {
            return "{\"state\":\"" + escapeJson(state) + "\",\"message\":\"" + escapeJson(message) + "\"}";
        }
    }
}
