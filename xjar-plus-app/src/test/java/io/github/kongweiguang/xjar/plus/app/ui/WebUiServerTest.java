/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.ui;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebUiServerTest {
    private static final Pattern TOKEN_PATTERN = Pattern.compile(
            "<meta\\b(?=[^>]*\\bname=\"xjp-session\")(?=[^>]*\\bcontent=\"([^\"]+)\")[^>]*>");

    /** 令牌提取必须绑定对应 meta 元素，且不依赖格式化器选择的属性顺序。 */
    @Test
    void tokenExtractionIgnoresAttributeOrderWithoutAcceptingAnotherMetaElement() {
        for (String attributes : new String[]{"name=\"xjp-session\" content=\"fixture-token\"",
                "content=\"fixture-token\" name=\"xjp-session\""}) {
            final Matcher matcher = TOKEN_PATTERN.matcher("<meta " + attributes + ">");
            assertTrue(matcher.find());
            assertEquals("fixture-token", matcher.group(1));
        }
        assertFalse(TOKEN_PATTERN.matcher("<meta name=\"xjp-session\"><meta content=\"wrong\">").find());
    }

    /**
     * 验证跨站浏览器即使知道回环地址也无法提交任务。
     */
    @Test
    void rejectsCrossOriginJobCreation() throws Exception {
        try (WebUiServer server = new WebUiServer(0)) {
            server.start();
            final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            final Session session = loadSession(client, server.origin());
            final HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            server.origin().resolve("/api/jobs"))
                    .header("Origin", "http://attacker.example")
                    .header("Cookie", session.cookie)
                    .header("X-XJP-Session", session.token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, response.statusCode());
            assertFalse(response.body().contains("\"id\""));
        }
    }

    /**
     * 验证 API 要求 CSRF 防护，且有效同源请求只进入校验、不打开系统对话框。
     */
    @Test
    void requiresSessionTokenForApiCalls() throws Exception {
        try (WebUiServer server = new WebUiServer(0)) {
            server.start();
            final HttpClient client = HttpClient.newHttpClient();
            final URI endpoint = server.origin().resolve("/api/jobs");
            final HttpResponse<String> withoutSession = client.send(HttpRequest.newBuilder(endpoint)
                    .header("Origin", server.origin().toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, withoutSession.statusCode());

            final Session session = loadSession(client, server.origin());
            final HttpResponse<String> invalidRequest = client.send(HttpRequest.newBuilder(endpoint)
                    .header("Origin", server.origin().toString())
                    .header("Cookie", session.cookie)
                    .header("X-XJP-Session", session.token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalidRequest.statusCode());
        }
    }

    /**
     * 只接受精确 JSON 媒体类型，并兼容标准 charset 参数及大小写差异。
     */
    @Test
    void validatesExactJsonMediaTypeAndCharsetParameter() throws Exception {
        try (WebUiServer server = new WebUiServer(0)) {
            server.start();
            final HttpClient client = HttpClient.newHttpClient();
            final Session session = loadSession(client, server.origin());
            final URI endpoint = server.origin().resolve("/api/jobs");
            final HttpResponse<String> jsonp = postJob(client, server, session, endpoint,
                    "application/jsonp", "{}");
            final HttpResponse<String> jsonWithCharset = postJob(client, server, session, endpoint,
                    "Application/JSON; charset=UTF-8", "{}");

            assertEquals(415, jsonp.statusCode());
            assertEquals(400, jsonWithCharset.statusCode());
        }
    }

    /**
     * 确认非法日期即使与密码相同也不会进入 HTTP 错误响应或服务标准错误输出。
     */
    @Test
    void malformedDateDoesNotEchoPasswordIntoResponseOrLogs() throws Exception {
        final String marker = "synthetic-secret-marker";
        final ByteArrayOutputStream capturedError = new ByteArrayOutputStream();
        final PrintStream originalError = System.err;
        try (PrintStream redirectedError = new PrintStream(capturedError, true, StandardCharsets.UTF_8);
             WebUiServer server = new WebUiServer(0)) {
            server.start();
            final HttpClient client = HttpClient.newHttpClient();
            final Session session = loadSession(client, server.origin());
            final String requestBody = """
                    {"mode":"standalone","input":"source.jar","output":"result.jar",
                     "password":"%s","notBefore":"%s"}
                    """.formatted(marker, marker);
            System.setErr(redirectedError);
            final HttpResponse<String> response = postJob(client, server, session,
                    server.origin().resolve("/api/jobs"), "application/json", requestBody);
            System.setErr(originalError);

            assertEquals(400, response.statusCode());
            assertFalse(response.body().contains(marker));
            assertFalse(capturedError.toString(StandardCharsets.UTF_8).contains(marker));
            assertTrue(response.body().contains("许可证时间格式无效"));
        } finally {
            System.setErr(originalError);
        }
    }

    /**
     * 保护取消接口，并确认空闲时取消请求不会创建原生对话框。
     */
    @Test
    void pickerCancellationRequiresSameOriginAndActivePicker() throws Exception {
        try (WebUiServer server = new WebUiServer(0)) {
            server.start();
            final HttpClient client = HttpClient.newHttpClient();
            final Session session = loadSession(client, server.origin());
            final URI endpoint = server.origin().resolve("/api/picker/cancel");
            final HttpResponse<String> crossOrigin = client.send(HttpRequest.newBuilder(endpoint)
                    .header("Origin", "http://attacker.example")
                    .header("Cookie", session.cookie)
                    .header("X-XJP-Session", session.token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofString());
            final HttpResponse<String> idleCancel = client.send(HttpRequest.newBuilder(endpoint)
                    .header("Origin", server.origin().toString())
                    .header("Cookie", session.cookie)
                    .header("X-XJP-Session", session.token)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(403, crossOrigin.statusCode());
            assertEquals(409, idleCancel.statusCode());
        }
    }

    /**
     * 在提供公开 HTML 页面前拒绝伪造的 Host 地址。
     */
    @Test
    void rejectsHostHeaderOutsideLoopbackAuthority() throws Exception {
        try (WebUiServer server = new WebUiServer(0)) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(2_000);
                socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: attacker.example\r\n"
                                                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                final String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                assertTrue(response.startsWith("HTTP/1.1 403"));
            }
        }
    }

    /**
     * 在解析 JSON 或密码内容占用工作线程前限制任务请求体大小。
     */
    @Test
    void rejectsOversizedJobBody() throws Exception {
        try (WebUiServer server = new WebUiServer(0)) {
            server.start();
            final HttpClient client = HttpClient.newHttpClient();
            final Session session = loadSession(client, server.origin());
            final String oversized = "x".repeat(256 * 1024 + 1);
            final HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                            server.origin().resolve("/api/jobs"))
                    .header("Origin", server.origin().toString())
                    .header("Cookie", session.cookie)
                    .header("X-XJP-Session", session.token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(oversized))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertEquals(413, response.statusCode());
        }
    }

    /**
     * 将队列容量拒绝作为明确 HTTP 响应返回，并保留可操作的原因。
     */
    @Test
    void reportsQueueRejectionAsDefiniteRateLimit() throws Exception {
        final CountDownLatch workerEntered = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), task -> {
            final Thread thread = new Thread(task, "xjar-plus-ui-server-test-worker");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        workers.execute(() -> {
            workerEntered.countDown();
            try {
                releaseWorker.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });
        final WebJobService jobs = new WebJobService(workers);
        try {
            assertTrue(workerEntered.await(2, TimeUnit.SECONDS));
            try (WebUiServer server = new WebUiServer(0, jobs)) {
                server.start();
                final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
                final Session session = loadSession(client, server.origin());
                final String requestBody = """
                        {"mode":"standalone","input":"source.jar","output":"result.jar","password":"secret"}
                        """;
                final HttpResponse<String> response = client.send(HttpRequest.newBuilder(
                                server.origin().resolve("/api/jobs"))
                        .header("Origin", server.origin().toString())
                        .header("Cookie", session.cookie)
                        .header("X-XJP-Session", session.token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(), HttpResponse.BodyHandlers.ofString());

                assertEquals(429, response.statusCode());
                assertTrue(response.body().contains("当前已有任务排队"));
            }
        } finally {
            releaseWorker.countDown();
            jobs.close();
        }
    }

    /**
     * 在同源 API 调用前按浏览器流程获取页面签发的会话令牌和 Cookie。
     */
    private Session loadSession(HttpClient client, URI origin) throws Exception {
        final HttpResponse<String> response = client.send(HttpRequest.newBuilder(origin.resolve("/"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        final Matcher matcher = TOKEN_PATTERN.matcher(response.body());
        assertTrue(matcher.find());
        final String cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        return new Session(matcher.group(1), cookie);
    }

    /**
     * 通过一致的同源凭据构造任务提交，确保回归测试走真实 HTTP 边界。
     */
    private HttpResponse<String> postJob(HttpClient client, WebUiServer server, Session session,
                                         URI endpoint, String contentType, String requestBody) throws Exception {
        return client.send(HttpRequest.newBuilder(endpoint)
                .header("Origin", server.origin().toString())
                .header("Cookie", session.cookie)
                .header("X-XJP-Session", session.token)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private record Session(String token, String cookie) {
    }
}
