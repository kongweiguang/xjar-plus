package fixture.solon;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.ServiceLoader;

import org.noear.solon.annotation.Controller;
import org.noear.solon.annotation.Mapping;

/**
 * 通过真实 HTTP 请求观察框架类加载、SPI 发现和应用资源访问结果。
 *
 * @author kongweiguang
 */
@Controller
public class ProbeController {
    /**
     * 只使用 JDK 的资源与 SPI 接口，避免辅助接口掩盖启动器的实际行为。
     */
    @Mapping("/probe")
    public String probe() throws IOException {
        StringBuilder services = new StringBuilder();
        Iterator<ProbeService> iterator = ServiceLoader.load(ProbeService.class).iterator();
        while (iterator.hasNext()) {
            if (services.length() > 0) {
                services.append(',');
            }
            services.append(iterator.next().name());
        }
        try (InputStream input = ProbeController.class.getClassLoader().getResourceAsStream("fixture/data.txt")) {
            if (input == null) {
                throw new IOException("Missing fixture/data.txt");
            }
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                content.write(buffer, 0, read);
            }
            return "SPI=" + services + "\nRESOURCE=" + new String(content.toByteArray(), StandardCharsets.UTF_8).trim();
        }
    }
}
