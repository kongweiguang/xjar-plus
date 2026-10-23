package fixture.plain;

/**
 * 测试进程仅通过 META-INF/services 加载此提供者，避免直接引用掩盖服务发现问题。
 *
 * @author kongweiguang
 */
public final class PlainProbeService implements ProbeService {
    /**
     * 返回固定标识，以区分 ServiceLoader 成功发现提供者和未发现任何提供者。
     */
    @Override
    public String name() {
        return "plain-provider";
    }
}
