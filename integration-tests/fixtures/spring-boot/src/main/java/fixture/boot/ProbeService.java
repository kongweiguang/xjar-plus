package fixture.boot;

/**
 * 将接口与提供者保留在 BOOT-INF/classes 中，以验证加密应用的类路径资源访问。
 *
 * @author kongweiguang
 */
public interface ProbeService {
    /**
     * 返回固定标识，使测试能够确认所选 Boot 类加载器读取了 SPI 描述文件。
     */
    String name();
}
