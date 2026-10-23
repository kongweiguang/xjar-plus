package fixture.plain;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.ServiceLoader;

/**
 * 提供无外部依赖的可执行 JAR，以便直接在 Java 8 上验证字节码与启动行为。
 *
 * @author kongweiguang
 */
public final class PlainFixture {
    private static final String RESOURCE = "/fixture/data.txt";

    /**
     * 仅使用 Java 8 接口，使同一原始产物和加密产物均可在 Java 8 及更新的 JDK 上运行。
     */
    public static void main(String[] args) throws Exception {
        String mode = args.length == 0 ? "" : args[0];
        if ("--serve".equals(mode)) {
            serve(port(args));
            return;
        }
        if ("--stdin".equals(mode)) {
            echoStandardInput();
            return;
        }
        System.out.println("APP=plain-java8");
        for (int index = 0; index < args.length; index++) {
            System.out.println("ARG=" + index + ":" + args[index]);
        }
        System.out.println("SPI=" + providers());
        System.out.println("RESOURCE=" + resourceText());
        System.out.println("MR=" + MultiReleaseProbe.version());
    }

    /**
     * 使用真实的回环 HTTP 服务，让兼容性测试经过 JAR 启动器验证类路径资源响应。
     */
    private static void serve(int port) throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/probe", exchange -> respond(exchange, 200, probeBytes()));
        server.createContext("/asset", exchange -> respond(exchange, 200, resourceBytes()));
        server.start();
        System.out.println("HTTP_READY=" + server.getAddress().getPort());
        Thread.currentThread().join();
    }

    /**
     * 保持 HTTP 样本实现精简，并为 GET 与 HEAD 设置一致的明确内容长度。
     */
    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
        if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    /**
     * 将 SPI 与类路径资源结果合并，让一次真实请求同时验证两项约定。
     */
    private static byte[] probeBytes() throws IOException {
        return ("SPI=" + providers() + "\nRESOURCE=" + resourceText() + "\nMR=" + MultiReleaseProbe.version())
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 要求显式端口参数，使测试可使用临时端口，避免依赖共享的默认端口。
     */
    private static int port(String[] args) {
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                return Integer.parseInt(arg.substring("--port=".length()));
            }
        }
        throw new IllegalArgumentException("--serve requires --port=<number>");
    }

    /**
     * 按运行时发现顺序输出逗号分隔的提供者标识，便于比较原始与加密产物。
     */
    private static String providers() {
        StringBuilder result = new StringBuilder();
        Iterator<ProbeService> services = ServiceLoader.load(ProbeService.class).iterator();
        while (services.hasNext()) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(services.next().name());
        }
        return result.toString();
    }

    /**
     * 通过应用类加载器读取资源，以便暴露运行时资源查找的回归问题。
     */
    private static String resourceText() throws IOException {
        return new String(resourceBytes(), StandardCharsets.UTF_8).trim();
    }

    /**
     * 只缓冲小型测试资源，因为测试需要比较完整字节，而非仅确认能否打开。
     */
    private static byte[] resourceBytes() throws IOException {
        try (InputStream input = PlainFixture.class.getResourceAsStream(RESOURCE);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IOException("Missing fixture resource " + RESOURCE);
            }
            byte[] buffer = new byte[128];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    /**
     * 仅在显式回显模式下读取标准输入，以证明加密运行时不会占用应用输入来读取口令。
     */
    private static void echoStandardInput() throws IOException {
        byte[] buffer = new byte[1024];
        int count;
        while ((count = System.in.read(buffer)) >= 0) {
            System.out.write(buffer, 0, count);
        }
        System.out.flush();
    }
}
