package fixture.solon;

/**
 * 此提供者仅允许通过标准 META-INF/services 资源发现，避免绕过 SPI 加载路径。
 *
 * @author kongweiguang
 */
public final class FixtureProbeService implements ProbeService {
    /**
     * 用固定标识验证标准 Solon 大包布局下的 SPI 加载是否成功。
     */
    @Override
    public String name() {
        return "solon-provider";
    }
}
