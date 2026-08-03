import io.xjar.XCryptos;
import io.xjar.XGo;
import io.xjar.XKit;
import io.xjar.key.XKey;
import io.xjar.utils.Platform;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class MyTest {
    public static void main(String[] args) throws Exception {
        testThreeGorgesLinuxPackage();
    }

    public static void testThreeGorgesLinuxPackage() throws Exception {
        String validStartDate = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        XCryptos.encryption()
                .inputJar("C:\\dev\\java\\xm\\three-gorges\\three-gorges-back\\three-gorges-start\\target\\three-gorges-start.jar")
                .password("woshimimao")
                .jarArgs("-XX:+DisableAttachMechanism")
                .jdkZip("C:\\Users\\24052\\.jdks\\jdk-21.0.2.zip")
                .goPath("C:\\Users\\24052\\sdk\\go1.24.3\\bin")
                .platform(Platform.LINUX_AMD64)
                .validStartDate(validStartDate)
                .validEndDate("2026-08-03 14:30:00")
                .code("three-gorges")
                .output("C:\\dev\\java\\xm\\three-gorges\\three-gorges-back\\three-gorges-start\\target\\three-gorges-linux-20260803-1430")
                .ok();
    }

    private static void example() throws Exception {
        // 加密jar包
        XCryptos.encryption()
                //原始jar
                .inputJar("C:\\dev\\java\\xm\\my\\boot-demo\\target\\boot-demo-0.0.1-SNAPSHOT.jar")
                //密码
                .password("woshimimao")
                //执行jar参数 最后执行结果是( java 自定义参数 -jar app.jar)
                //建议加上-XX:+DisableAttachMechanism 不然可以使用agent获取jvm运行时的class信息进行反编译（通过类似arthas的工具实现）
                .jarArgs("-XX:+DisableAttachMechanism")
                //jdk的zip包地址（需要放到目标机器执行的jdk版本，一般是linux下的jdk），注意：jdk的zip包不能有多层目录，只能是jdk的根目录
                .jdkZip("C:\\dev\\java\\env\\jdk\\liberica-21.0.5.zip")
                //需要加密路径
                .include("org/example/**")
                .include("/**.yml")
                //需要排除路径
                .exclude("/static/**/*")
                //构建机器上的golang的路径（运行的机器上不需要go环境）
                .goPath("C:\\dev\\golang\\env\\go1.23.4\\bin")
                //选择打包目标平台
                .platform(Platform.WINDOWS_AMD64)
                //（格式必须是yyyy-MM-dd HH:mm:ss）启动器有效期时间，简单判断时间，如果宿主机修改机器时间就没办法了，推荐网络校验
                .validStartDate("2025-01-01 00:00:00")
                .validEndDate("2026-10-01 00:00:00")
                //生成license文件内的code，如果有license文件，会校验license中的code和启动器重的code一致，否则会认为这个license文件不是这个启动器的，不填默认生成uuid
                //建议每一个应用一个code
                .code("123123")
                //打包到boboji目标路径
                .output("C:\\dev\\java\\xm\\my\\boot-demo\\target\\boboji")
                .ok();


    }

    private static void genLicense() throws NoSuchAlgorithmException, IOException {
        // 单独生成license文件，只要保证密码和code一致，即可修改启动器的启动参数和有效期
        XKey key = XKit.key("woshimimao");
        XGo.license("C:\\dev\\java\\xm\\boot3-dev\\target\\boboji\\miyao",
                key,
                "123123",
                "-Xms512m -Xmx1024m -Dserver.port=8083",
                "2025-01-01 00:00:00",
                "2026-10-01 00:00:00");
    }
}
