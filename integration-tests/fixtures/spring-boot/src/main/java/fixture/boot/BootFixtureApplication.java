package fixture.boot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 采用 Spring Boot 2、3、4 长期共用的启动接口，避免样本依赖特定版本入口。
 *
 * @author kongweiguang
 */
@SpringBootApplication
public class BootFixtureApplication {
    /**
     * 原样传递启动参数，使同一可执行样本适用于不同版本的重打包测试。
     */
    public static void main(String[] args) {
        SpringApplication.run(BootFixtureApplication.class, args);
    }
}
