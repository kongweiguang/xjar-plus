package fixture.solon;

/**
 * 由测试样本定义提供者接口，以便比较普通启动与加密启动时的服务描述结果。
 *
 * @author kongweiguang
 */
public interface ProbeService {
    /**
     * 返回固定提供者标识，供 HTTP 端点断言核对实际加载结果。
     */
    String name();
}
