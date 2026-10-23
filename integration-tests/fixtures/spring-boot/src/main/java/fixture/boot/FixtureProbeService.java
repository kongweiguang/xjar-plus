package fixture.boot;

/**
 * 使用最小提供者，通过端点验证加密后的 Boot 归档仍能访问服务描述资源。
 *
 * @author kongweiguang
 */
public final class FixtureProbeService implements ProbeService {
    /**
     * 返回测试提供者标识，避免引入框架特定版本的行为。
     */
    @Override
    public String name() {
        return "boot-provider";
    }
}
