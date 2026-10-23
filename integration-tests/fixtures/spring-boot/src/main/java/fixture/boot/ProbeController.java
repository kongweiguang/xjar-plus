package fixture.boot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.ServiceLoader;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通过 HTTP 暴露类加载、SPI 和资源读取结果，避免仅凭框架启动成功就判定兼容。
 *
 * @author kongweiguang
 */
@RestController
public class ProbeController {
    /**
     * 生成稳定的内容标识，使条件请求比较资源内容而非构建时间戳。
     */
    private static String hex(byte[] value) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int current = value[index] & 0xff;
            output[index * 2] = digits[current >>> 4];
            output[index * 2 + 1] = digits[current & 0x0f];
        }
        return new String(output);
    }

    /**
     * 通过可执行归档实际使用的框架类加载器读取应用的服务描述与普通资源。
     */
    @GetMapping("/probe")
    public String probe() throws IOException {
        StringBuilder services = new StringBuilder();
        Iterator<ProbeService> iterator = ServiceLoader.load(ProbeService.class).iterator();
        while (iterator.hasNext()) {
            if (services.length() > 0) {
                services.append(',');
            }
            services.append(iterator.next().name());
        }
        String resource;
        try (java.io.InputStream input = new ClassPathResource("fixture/data.txt").getInputStream()) {
            resource = StreamUtils.copyToString(input, StandardCharsets.UTF_8).trim();
        }
        return "SPI=" + services + "\nRESOURCE=" + resource;
    }

    /**
     * 通过 Boot 的嵌套 JAR 类加载器解析类，以验证加密后真实的多版本条目选择。
     */
    @GetMapping("/mr-probe")
    public String multiReleaseProbe() throws ReflectiveOperationException {
        Class<?> probe = Class.forName("fixture.mr.BootMultiReleaseProbe", true, getClass().getClassLoader());
        return String.valueOf(probe.getMethod("value").invoke(null));
    }

    /**
     * 为二进制测试资源提供 ETag 端点，避免依赖在 Boot 3 中更换命名空间的 Servlet 接口。
     */
    @GetMapping("/etag/{name}")
    public ResponseEntity<byte[]> etag(@PathVariable("name") String name,
                                       @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch)
            throws IOException, NoSuchAlgorithmException {
        if (!java.util.Arrays.asList("asset.txt", "empty.bin", "block16.bin", "large.bin").contains(name)) {
            return ResponseEntity.notFound().build();
        }
        byte[] body;
        try (java.io.InputStream input = new ClassPathResource("static/" + name).getInputStream()) {
            body = StreamUtils.copyToByteArray(input);
        }
        String tag = '"' + hex(MessageDigest.getInstance("SHA-256").digest(body)) + '"';
        if (tag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header("ETag", tag).build();
        }
        return ResponseEntity.ok().header("ETag", tag).contentLength(body.length).body(body);
    }
}
