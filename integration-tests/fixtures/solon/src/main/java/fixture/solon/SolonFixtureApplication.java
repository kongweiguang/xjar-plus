package fixture.solon;

import org.noear.solon.Solon;
import org.noear.solon.annotation.SolonMain;

/**
 * 采用 Solon 文档规定的注解式可执行 JAR 入口，避免测试依赖非标准启动方式。
 *
 * @author kongweiguang
 */
@SolonMain
public class SolonFixtureApplication {
    /**
     * 由 Solon 自身启动流程处理参数和类加载，以验证受支持的嵌套 JAR 布局。
     */
    public static void main(String[] args) {
        Solon.start(SolonFixtureApplication.class, args);
    }
}
