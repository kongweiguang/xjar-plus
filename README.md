# xjar-plus(基于xjar修改)

## 支持springboot3.4.2+jdk21

```java
import io.xjar.XCryptos;
import org.junit.Test;

public class MyTest {
    @Test
    public void test1() throws Exception {
        XCryptos.encryption()
                //原始jar
                .from("D:\\bwy\\java\\xm\\ai-open-platform-back\\ai-open-platform-start\\target\\ai-open-platform-start.jar")
                //密码
                .password("woshimimao")
                //执行jar参数
                .jarArgs("-XX:+DisableAttachMechanism --add-opens java.base/jdk.internal.loader=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED")
                //jdk的zip包地址
                .jdkZip("D:\\bwy\\java\\env\\graalvm-ce-21.0.2.zip")
                //需要加密路径
                .include("/cn/edu/pku/whai/**")
                .include("BOOT-INF/classes/application**")
                //需要排除路径
                .exclude("/static/**/*")
                //golang的路径
                .goPath("D:\\bwy\\java\\xm\\dev\\src\\main\\resources\\go1.24.1\\bin")
                //是否打包为linux可执行文件
                .pkgLinux()
                //打包到目标路径
                .to("D:\\bwy\\java\\xm\\ai-open-platform-back\\ai-open-platform-start\\target\\boboji", "bala.jar");
    }
}
```