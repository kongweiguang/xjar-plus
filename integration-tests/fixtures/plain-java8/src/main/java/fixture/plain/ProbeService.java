package fixture.plain;

/**
 * 使用精简的 SPI 观测服务发现行为，避免为 Java 8 样本引入框架依赖。
 *
 * @author kongweiguang
 */
public interface ProbeService {
    /**
     * 用提供者标识验证发现结果，让集成测试能够识别服务描述资源查找失败。
     */
    String name();
}
