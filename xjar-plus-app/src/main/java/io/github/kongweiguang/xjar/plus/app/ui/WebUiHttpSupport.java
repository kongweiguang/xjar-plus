/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.ui;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import io.github.kongweiguang.xjar.plus.app.json.JsonSupport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 将可复用的 HTTP 封装和浏览器来源校验从服务生命周期类中分离。
 */
final class WebUiHttpSupport {
    static final int MAX_BODY_BYTES = 256 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 禁止创建实例；此类仅归集无状态的 HTTP 边界操作。
     */
    private WebUiHttpSupport() {
    }

    /**
     * 只接受完全匹配的回环地址，避免 DNS 重绑定和 Host 混淆转发请求。
     */
    static boolean validHost(HttpExchange exchange, int port) {
        return ("127.0.0.1:" + port).equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Host"));
    }

    /**
     * 以恒定时间比较 Cookie 与页面令牌，防止依赖浏览器自动携带 Cookie 的 CSRF。
     */
    static boolean validSession(HttpExchange exchange, String sessionToken) {
        final String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        final String header = exchange.getRequestHeaders().getFirst("X-XJP-Session");
        if (cookie == null || header == null) {
            return false;
        }
        String value = null;
        for (String part : cookie.split(";")) {
            final String candidate = part.strip();
            if (candidate.startsWith("xjp_session=")) {
                value = candidate.substring("xjp_session=".length());
            }
        }
        return value != null && sameSecret(sessionToken, value) && sameSecret(sessionToken, header);
    }

    /**
     * 要求 Origin 完全匹配；仅对不修改状态的读取允许省略该字段。
     */
    static boolean validOrigin(HttpExchange exchange, String expectedOrigin, boolean required) {
        final String requestOrigin = exchange.getRequestHeaders().getFirst("Origin");
        if (requestOrigin == null) {
            return !required;
        }
        return requestOrigin.equals(expectedOrigin);
    }

    /**
     * 即使请求伪造 Host，也拒绝被浏览器标记为跨站的 fetch 请求。
     */
    static boolean validFetchSite(HttpExchange exchange, String expectedOrigin) {
        final String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
        return !"cross-site".equalsIgnoreCase(site) && validOrigin(exchange, expectedOrigin, false);
    }

    /**
     * 按完整媒体类型校验 JSON，避免前缀相似类型绕过请求体格式边界。
     */
    static boolean isJson(HttpExchange exchange) {
        final String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null) {
            return false;
        }
        final int parameterStart = contentType.indexOf(';');
        final String mediaType = (parameterStart < 0 ? contentType : contentType.substring(0, parameterStart)).strip();
        return "application/json".equalsIgnoreCase(mediaType);
    }

    /**
     * 限制请求体分配大小，避免本机异常请求消耗无界内存。
     */
    static byte[] readBoundedBody(HttpExchange exchange) throws IOException {
        final byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            throw new IOException("请求超过 256 KiB 限制。");
        }
        return body;
    }

    /**
     * 以统一 JSON 格式响应任务创建和状态查询，并沿用禁止缓存的安全策略。
     */
    static void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8", JsonSupport.write(value, false));
    }

    /**
     * 使用 UTF-8 写出文本，确保路径和校验消息中的国际字符不被破坏。
     */
    static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        send(exchange, status, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 为所有绑定会话的响应设置 no-store 后再写出字节。
     */
    static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        final Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /**
     * 增加浏览器安全响应头作为纵深防御，不把客户端校验当作信任边界。
     */
    static void addSecurityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; " +
                "img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; " +
                                               "form-action 'self'; frame-ancestors 'none'");
    }

    /**
     * 只读取打包时固定的静态资源，不依赖进程工作目录。
     */
    static String readResource(String resource) throws IOException {
        try (var input = WebUiHttpSupport.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("缺少 UI 资源：" + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 为每个本地界面服务进程生成新的高熵会话令牌。
     */
    static String newSessionToken() {
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 空值检查后以恒定时间比较令牌，减少因比较耗时泄露差异。
     */
    private static boolean sameSecret(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 返回单行有界消息，不向浏览器暴露原始请求体。
     */
    static String safeMessage(String value) {
        if (value == null || value.isBlank()) {
            return "请求数据无效。";
        }
        final String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").strip();
        return normalized.substring(0, Math.min(300, normalized.length()));
    }
}
