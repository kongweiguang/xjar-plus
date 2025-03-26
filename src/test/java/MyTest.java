import io.xjar.XCryptos;
import org.junit.Test;

public class MyTest {
    @Test
    public void test1() throws Exception {
        XCryptos.encryption()
                .from("D:\\bwy\\java\\xm\\ai-open-platform-back\\ai-open-platform-start\\target\\ai-open-platform-start.jar")
                .password("woshimimao")
                .jarArgs("-XX:+DisableAttachMechanism --add-opens java.base/jdk.internal.loader=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED")
                .jdkZip("D:\\bwy\\java\\env\\graalvm-ce-21.0.2.zip")
                .include("/cn/edu/pku/whai/**")
                .exclude("/static/**/*")
                .to("D:\\bwy\\java\\xm\\ai-open-platform-back\\ai-open-platform-start\\target\\boboji", "balabala");
    }
}
