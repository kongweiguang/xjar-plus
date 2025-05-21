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
                .from("C:\\dev\\java\\xm\\boot3-dev\\target\\boot3-dev-0.0.1-SNAPSHOT.jar")
                //密码
                .password("woshimimao")
                //执行jar参数 最后执行结果是 java 自定义参数 -jar app.jar
                //建议加上-XX:+DisableAttachMechanism 不然可以使用agent获取jvm运行时的class信息进行反编译（通过类似arthas的工具实现）
                .jarArgs("-XX:+DisableAttachMechanism --add-opens java.base/jdk.internal.loader=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.net=ALL-UNNAMED")
                //jdk的zip包地址（需要放到目标机器执行的jdk版本，一般是linux下的jdk），注意：jdk的zip包不能有多层目录，只能是jdk的根目录
                .jdkZip("C:\\Users\\24052\\.jdks\\liberica-21.0.7.zip")
                //需要加密路径
                .include("org/example/boot3dev/**")
                .include("/**.yml")
                //需要排除路径
                .exclude("/static/**/*")
                //构建机器上的golang的路径（运行的机器上不需要go环境）
                .goPath("C:\\Users\\24052\\sdk\\go1.24.3\\bin")
                //是否打包为linux可执行文件
                .pkgLinux()
                //（格式必须是yyyy-MM-dd HH:mm:ss）启动器有效期时间，简单判断时间，如果宿主机修改机器时间就没办法了，推荐网络校验
                .validStartDate("2025-01-01 00:00:00")
                .validEndDate("2025-10-01 00:00:00")
                //打包到boboji目标路径
                .to("C:\\dev\\java\\xm\\boot3-dev\\target\\boboji", "bala.jar");
    }
}
```