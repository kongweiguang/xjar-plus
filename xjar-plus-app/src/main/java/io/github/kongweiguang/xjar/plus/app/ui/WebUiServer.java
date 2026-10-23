/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.kongweiguang.xjar.plus.app.json.JsonSupport;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在回环地址提供本地界面，并通过严格同源会话校验和有界任务服务保护入口。
 */
public final class WebUiServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor requests;
    private final WebJobService jobs;
    private final NativePathPicker picker = new NativePathPicker();
    private final String sessionToken = WebUiHttpSupport.newSessionToken();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private Thread shutdownHook;

    /**
     * 仅绑定 IPv4 回环地址并限制 HTTP 工作线程，默认不对远端开放服务。
     */
    public WebUiServer(int port) throws IOException {
        this(port, new WebJobService());
    }

    /**
     * 注入有界任务服务，使容量拒绝可以通过真实 HTTP 边界测试。
     */
    WebUiServer(int port, WebJobService jobs) throws IOException {
        final WebJobService configuredJobs = Objects.requireNonNull(jobs, "jobs");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        this.jobs = configuredJobs;
        final ThreadFactory factory = task -> {
            final Thread thread = new Thread(task, "xjar-plus-ui-http");
            thread.setDaemon(true);
            return thread;
        };
        requests = new ThreadPoolExecutor(4, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), factory, new ThreadPoolExecutor.AbortPolicy());
        server.createContext("/", this::handle);
        server.setExecutor(requests);
    }

    /**
     * 启动服务并注册清理钩子，使任务和原生对话框随进程一同停止。
     */
    public void start() {
        if (closed.get()) {
            throw new IllegalStateException("本地界面服务已关闭。");
        }
        server.start();
        shutdownHook = new Thread(this::close, "xjar-plus-ui-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * 返回实际绑定端口，使临时端口启动和黑盒测试共用同一条路径。
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * 等待显式关闭或进程退出，避免界面使用期间服务提前结束。
     */
    public void await() throws InterruptedException {
        stopped.await();
    }

    /**
     * 构造服务唯一接受的浏览器来源。
     */
    public URI origin() {
        return URI.create("http://127.0.0.1:" + port());
    }

    /**
     * 负责关闭 HTTP 交换对象，并将路由错误转换为有界响应且不泄露请求数据。
     */
    private void handle(HttpExchange exchange) {
        try (exchange) {
            try {
                WebUiHttpSupport.addSecurityHeaders(exchange.getResponseHeaders());
                route(exchange);
            } catch (RuntimeException e) {
                WebUiHttpSupport.sendJson(exchange, 400, Map.of("error", "请求数据无效，请检查字段后重试。"));
            }
        } catch (IOException e) {
            if (!closed.get()) {
                System.err.println("本地界面请求失败：" + e.getClass().getSimpleName());
            }
        }
    }

    /**
     * 将页面资源与需认证的 API 分开路由，仅 API 调用要求会话令牌。
     */
    private void route(HttpExchange exchange) throws IOException {
        if (!WebUiHttpSupport.validHost(exchange, port())) {
            WebUiHttpSupport.sendJson(exchange, 403, Map.of("error", "仅允许通过本机地址访问。"));
            return;
        }
        final String path = exchange.getRequestURI().getPath();
        if (path.equals("/")) {
            serveIndex(exchange);
            return;
        }
        if (path.equals("/app.js") || path.equals("/app.css")) {
            serveAsset(exchange, path);
            return;
        }
        routeApi(exchange, path);
    }

    /**
     * 分发状态修改或状态查询接口前，先完成会话和来源校验。
     */
    private void routeApi(HttpExchange exchange, String path) throws IOException {
        if (!path.startsWith("/api/") || !WebUiHttpSupport.validSession(exchange, sessionToken)
            || !WebUiHttpSupport.validFetchSite(exchange, origin().toString())) {
            WebUiHttpSupport.sendJson(exchange, 403,
                    Map.of("error", "会话无效或请求来源不匹配，请刷新本地页面。"));
            return;
        }
        if (routeJobs(exchange, path) || routePicker(exchange, path)) {
            return;
        }
        WebUiHttpSupport.sendJson(exchange, 404, Map.of("error", "接口不存在。"));
    }

    /**
     * 将任务接口集中路由，使安全校验保持统一且避免分发器过度膨胀。
     */
    private boolean routeJobs(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/api/jobs") && "POST".equals(exchange.getRequestMethod())) {
            createJob(exchange);
            return true;
        } else if (path.startsWith("/api/jobs/") && path.endsWith("/cancel")
                   && "POST".equals(exchange.getRequestMethod())) {
            cancelJob(exchange, path);
            return true;
        } else if (path.startsWith("/api/jobs/") && "GET".equals(exchange.getRequestMethod())) {
            getJob(exchange, path);
            return true;
        }
        return false;
    }

    /**
     * 单独路由文件选择操作，确保只有用户显式操作才会打开或取消系统对话框。
     */
    private boolean routePicker(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/api/picker/cancel") && "POST".equals(exchange.getRequestMethod())) {
            cancelPathPicker(exchange);
            return true;
        } else if (path.equals("/api/picker") && "POST".equals(exchange.getRequestMethod())) {
            choosePath(exchange);
            return true;
        }
        return false;
    }

    /**
     * 仅在同源页面导航时签发当前进程专属的会话 Cookie。
     */
    private void serveIndex(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            WebUiHttpSupport.sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }
        if (!WebUiHttpSupport.validOrigin(exchange, origin().toString(), false)) {
            WebUiHttpSupport.sendJson(exchange, 403, Map.of("error", "页面来源不匹配。"));
            return;
        }
        final String html = WebUiHttpSupport.readResource("/ui/index.html")
                .replace("@@SESSION_TOKEN@@", sessionToken);
        exchange.getResponseHeaders().add("Set-Cookie", "xjp_session=" + sessionToken
                                                        + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200");
        WebUiHttpSupport.send(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 仅提供两个固定静态资源，不会将用户路径解析到资源目录下。
     */
    private void serveAsset(HttpExchange exchange, String path) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            WebUiHttpSupport.sendJson(exchange, 405, Map.of("error", "Method Not Allowed"));
            return;
        }
        final String resource = "/ui" + path;
        final String type = path.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/css; charset=utf-8";
        WebUiHttpSupport.send(exchange, 200, type, WebUiHttpSupport.readResource(resource));
    }

    /**
     * 创建有界任务并仅返回不透明标识，避免凭据进入传输响应。
     */
    private void createJob(HttpExchange exchange) throws IOException {
        if (!WebUiHttpSupport.validOrigin(exchange, origin().toString(), true)) {
            WebUiHttpSupport.sendJson(exchange, 403, Map.of("error", "跨站请求已拒绝。"));
            return;
        }
        if (!WebUiHttpSupport.isJson(exchange)) {
            WebUiHttpSupport.sendJson(exchange, 415, Map.of("error", "请求必须使用 application/json。"));
            return;
        }
        final byte[] body;
        try {
            body = WebUiHttpSupport.readBoundedBody(exchange);
        } catch (IOException e) {
            WebUiHttpSupport.sendJson(exchange, 413,
                    Map.of("error", WebUiHttpSupport.safeMessage(e.getMessage())));
            return;
        }
        try {
            final String id = jobs.submit(body);
            WebUiHttpSupport.sendJson(exchange, 202, Map.of("id", id));
        } catch (WebJobService.RejectedJobException e) {
            WebUiHttpSupport.sendJson(exchange, 429, Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            WebUiHttpSupport.sendJson(exchange, 400,
                    Map.of("error", WebUiHttpSupport.safeMessage(e.getMessage())));
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    /**
     * 调用方提供绑定会话的 CSRF 令牌后才返回任务轮询状态。
     */
    private void getJob(HttpExchange exchange, String path) throws IOException {
        final String id = path.substring("/api/jobs/".length());
        if (id.contains("/") || id.isBlank()
            || !WebUiHttpSupport.validOrigin(exchange, origin().toString(), false)) {
            WebUiHttpSupport.sendJson(exchange, 403, Map.of("error", "任务查询来源无效。"));
            return;
        }
        final Object view = jobs.get(id);
        if (view == null) {
            WebUiHttpSupport.sendJson(exchange, 404, Map.of("error", "任务不存在或已过期。"));
            return;
        }
        WebUiHttpSupport.sendJson(exchange, 200, view);
    }

    /**
     * 协作式取消任务，使核心可以安全完成或回滚输出事务。
     */
    private void cancelJob(HttpExchange exchange, String path) throws IOException {
        if (!WebUiHttpSupport.validOrigin(exchange, origin().toString(), true)) {
            WebUiHttpSupport.sendJson(exchange, 403, Map.of("error", "跨站请求已拒绝。"));
            return;
        }
        final String id = path.substring("/api/jobs/".length(), path.length() - "/cancel".length());
        if (id.isBlank() || id.contains("/") || !jobs.cancel(id)) {
            WebUiHttpSupport.sendJson(exchange, 404, Map.of("error", "任务不存在或已结束。"));
            return;
        }
        WebUiHttpSupport.sendJson(exchange, 202, Map.of("accepted", true));
    }

    /**
     * 仅在同源校验通过后打开系统原生选择器，并将取消保留为空结果。
     */
    private void choosePath(HttpExchange exchange) throws IOException {
        if (!WebUiHttpSupport.validOrigin(exchange, origin().toString(), true)
            || !WebUiHttpSupport.isJson(exchange)) {
            WebUiHttpSupport.sendJson(exchange, 403, Map.of("error", "选择器请求来源无效。"));
            return;
        }
        final byte[] body;
        try {
            body = WebUiHttpSupport.readBoundedBody(exchange);
        } catch (IOException e) {
            WebUiHttpSupport.sendJson(exchange, 413,
                    Map.of("error", WebUiHttpSupport.safeMessage(e.getMessage())));
            return;
        }
        final JsonNode json;
        try {
            json = JsonSupport.readObject(body);
        } catch (RuntimeException e) {
            WebUiHttpSupport.sendJson(exchange, 400, Map.of("error", "选择器请求格式无效。"));
            return;
        }
        final String requestedKind;
        try {
            requestedKind = JsonSupport.optionalText(json, "kind");
        } catch (IllegalArgumentException e) {
            WebUiHttpSupport.sendJson(exchange, 400, Map.of("error", "选择器请求格式无效。"));
            return;
        }
        final String kind = requestedKind == null ? "" : requestedKind;
        try {
            final String selected = picker.choose(kind);
            WebUiHttpSupport.sendJson(exchange, 200,
                    selected == null ? Map.of("cancelled", true) : Map.of("path", selected));
        } catch (NativePathPicker.PickerCancelledException e) {
            WebUiHttpSupport.sendJson(exchange, 200, Map.of("cancelled", true));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            WebUiHttpSupport.sendJson(exchange, 503, Map.of("error", "系统选择窗口已取消。"));
        } catch (IOException | IllegalArgumentException e) {
            WebUiHttpSupport.sendJson(exchange, 503,
                    Map.of("error", WebUiHttpSupport.safeMessage(e.getMessage())));
        }
    }

    /**
     * 同源用户请求只会停止当前原生辅助进程，不会关闭整个界面服务。
     */
    private void cancelPathPicker(HttpExchange exchange) throws IOException {
        if (!WebUiHttpSupport.validOrigin(exchange, origin().toString(), true)) {
            WebUiHttpSupport.sendJson(exchange, 403, Map.of("error", "跨站请求已拒绝。"));
            return;
        }
        if (!picker.cancelCurrent()) {
            WebUiHttpSupport.sendJson(exchange, 409, Map.of("error", "当前没有打开的系统选择窗口。"));
            return;
        }
        WebUiHttpSupport.sendJson(exchange, 202, Map.of("accepted", true));
    }

    /**
     * 将原生选择器、协作式任务、HTTP 工作线程和服务作为一个生命周期整体停止。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        picker.close();
        jobs.close();
        server.stop(0);
        requests.shutdownNow();
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 已开始关闭，当前钩子将继续负责清理。
            }
        }
        stopped.countDown();
    }
}
