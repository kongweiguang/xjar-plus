package io.xjar;

import io.xjar.filter.XAllEntryFilter;
import io.xjar.filter.XAnyEntryFilter;
import io.xjar.filter.XMixEntryFilter;
import io.xjar.key.XKey;
import io.xjar.utils.Platform;
import org.apache.commons.compress.archivers.jar.JarArchiveEntry;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.xjar.XFilters.*;

/**
 * XJar 加密配置器。
 * <p>
 * 该类使用链式 API 收集原始 Jar、密码、资源过滤规则、JDK 包和 Go 交叉编译参数，
 * 最后通过 {@link #ok()} 执行加密、生成启动器并打包可执行文件。
 *
 * @author Payne 646742615@qq.com
 * 2020/4/30 17:05
 */
public class XEncryption {
    private static final String DEFAULT_APP_NAME = "app.jar";
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private XKey key;
    private final XAnyEntryFilter<JarArchiveEntry> includes = XKit.any();
    private final XAllEntryFilter<JarArchiveEntry> excludes = XKit.all();
    private String inputJar;
    private String output;
    private String jarArgs = "";
    private String jdkZip;
    private String goPath;
    private String validStartDate = LocalDateTime.now().format(DATE_TIME_FORMATTER);
    private String validEndDate = LocalDateTime.of(9999, 12, 31, 23, 59, 59).format(DATE_TIME_FORMATTER);
    private String code = UUID.randomUUID().toString();
    private Platform platform = Platform.WINDOWS_AMD64;

    /**
     * 指定原文包路径
     *
     * @param jar 待加密的原始 Jar 文件路径
     * @return {@code this}
     */
    public XEncryption inputJar(String jar) {
        this.inputJar = jar;
        return this;
    }


    /**
     * 指定密码
     *
     * @param password 密码
     * @return {@code this}
     */
    public XEncryption password(String password) {
        try {
            this.key = XKit.key(password);
            return this;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 指定高级密码
     *
     * @param algorithm 算法名称
     * @param keysize   密钥长度
     * @param ivsize    向量长度
     * @param password  加密密码
     * @return {@code this}
     */
    public XEncryption password(String algorithm, int keysize, int ivsize, String password) {
        try {
            this.key = XKit.key(algorithm, keysize, ivsize, password);
            return this;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 指定包含资源的ANT表达式, 可指定多个.
     *
     * @param ant 包含资源的ANT表达式
     * @return {@code this}
     */
    public XEncryption include(String ant) {
        includes.mix(ant(ant));
        return this;
    }

    /**
     * 指定包含资源的正则表达式, 可指定多个.
     *
     * @param regex 包含资源的正则表达式
     * @return {@code this}
     */
    public XEncryption include(Pattern regex) {
        includes.mix(regex(regex.pattern()));
        return this;
    }

    /**
     * 指定排除资源的ANT表达式, 可指定多个.
     *
     * @param ant 排除资源的ANT表达式
     * @return {@code this}
     */
    public XEncryption exclude(String ant) {
        excludes.mix(not(ant(ant)));
        return this;
    }

    /**
     * 指定排除资源的正则表达式, 可指定多个.
     *
     * @param regex 排除资源的正则表达式
     * @return {@code this}
     */
    public XEncryption exclude(Pattern regex) {
        excludes.mix(not(regex(regex.pattern())));
        return this;
    }

    /**
     * 指定密文包文件路径, 并执行加密.
     *
     * @param output 加密产物输出目录
     * @return {@code this}
     * @throws Exception 加密异常
     */
    public XEncryption output(String output) throws Exception {
        this.output = output;
        return this;
    }

    /**
     * 指定启动 Jar 时附加到 java 命令后的参数。
     * <p>
     * 示例: {@code -Xms512m -Xmx1024m -Dserver.port=8080}
     *
     * @param jarArgs 启动参数，默认为空字符串
     * @return {@code this}
     */
    public XEncryption jarArgs(String jarArgs) {
        this.jarArgs = jarArgs;
        return this;
    }

    /**
     * 指定需要随可执行文件一起分发的 JDK 压缩包。
     * <p>
     * 该文件会被复制到 {@code output/resource/jdk.zip}。
     *
     * @param jdkZip JDK zip 文件路径
     * @return {@code this}
     */
    public XEncryption jdkZip(String jdkZip) {
        this.jdkZip = jdkZip;
        return this;
    }

    /**
     * 指定 Go 构建目标平台。
     * <p>
     * 该值会转换为 Go 的 {@code GOOS} 和 {@code GOARCH} 环境变量。
     *
     * @param platform 目标平台，默认 {@link Platform#WINDOWS_AMD64}
     * @return {@code this}
     */
    public XEncryption platform(Platform platform) {
        this.platform = platform;
        return this;
    }

    /**
     * 指定构建机器上的 Go bin 目录。
     * <p>
     * 方法内部会调用 {@code goPath/go build main.go}，目标机器运行时不需要 Go 环境。
     *
     * @param goPath Go bin 目录路径
     * @return {@code this}
     */
    public XEncryption goPath(String goPath) {
        this.goPath = goPath;
        return this;
    }

    /**
     * 指定启动器有效期开始时间。
     *
     * @param date 开始时间，格式为 {@code yyyy-MM-dd HH:mm:ss}
     * @return {@code this}
     */
    public XEncryption validStartDate(String date) {
        this.validStartDate = date;
        return this;
    }

    /**
     * 指定启动器有效期结束时间。
     *
     * @param date 结束时间，格式为 {@code yyyy-MM-dd HH:mm:ss}
     * @return {@code this}
     */
    public XEncryption validEndDate(String date) {
        this.validEndDate = date;
        return this;
    }

    /**
     * 指定启动器和 license 绑定的应用编码。
     * <p>
     * 不指定时默认生成随机 UUID。同一应用重新生成 license 时，需要保持 code 一致。
     *
     * @param code 应用编码
     * @return {@code this}
     */
    public XEncryption code(String code) {
        this.code = code;
        return this;
    }

    /**
     * 执行完整加密流程。
     * <p>
     * 流程包括: 参数校验、复制 JDK、加密原始 Jar、生成 Go 启动器源码、
     * 调用 Go 编译可执行文件。
     *
     * @throws Exception 任一步骤失败时抛出异常
     */
    public void ok() throws Exception {
        validate();

        System.out.println("开始加密。。。");
        XMixEntryFilter<JarArchiveEntry> filter = buildFilter();
        Path resource = Paths.get(output, "resource");
        Files.createDirectories(resource);
        addJdk(output);

        XCryptos.encrypt(new File(inputJar), resource.resolve(DEFAULT_APP_NAME).toFile(), key, filter);

        System.out.println("加密完成。。。");

        XGo.make(output, key, code, DEFAULT_APP_NAME, jarArgs, validStartDate, validEndDate);

        System.out.println("开始打包。。。");

        buildPKG(output);
    }

    /**
     * 根据 include/exclude 配置构建最终过滤器。
     *
     * @return 未配置过滤规则时返回 {@code null}，表示使用默认加密范围
     */
    private XMixEntryFilter<JarArchiveEntry> buildFilter() {
        if (includes.size() == 0 && excludes.size() == 0) {
            return null;
        }

        XMixEntryFilter<JarArchiveEntry> filter = XKit.all();
        if (includes.size() > 0) {
            filter.mix(includes);
        }
        if (excludes.size() > 0) {
            filter.mix(excludes);
        }
        return filter;
    }

    /**
     * 校验执行加密流程所需的必要参数，尽早暴露配置问题。
     */
    private void validate() {
        requireText(inputJar, "inputJar is blank. [please call inputJar(String jar) before]");
        requireText(output, "output is blank. [please call output(String output) before]");
        requireText(jdkZip, "jdkZip is blank. [please call jdkZip(String jdkZip) before]");
        requireText(goPath, "goPath is blank. [please call goPath(String goPath) before]");
        if (key == null) {
            throw new IllegalArgumentException("key to encrypt is null. [please call password(String password) before]");
        }
        if (platform == null) {
            throw new IllegalArgumentException("platform is null. [please call platform(Platform platform) with a non-null value]");
        }
    }

    /**
     * 校验字符串参数不能为空。
     *
     * @param value   待校验的字符串
     * @param message 校验失败时抛出的错误信息
     */
    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 调用 Go 编译输出目录中的 {@code main.go}。
     *
     * @param output 包含 {@code main.go} 的输出目录
     * @throws Exception Go 进程启动失败或构建返回非 0 退出码时抛出异常
     */
    private void buildPKG(String output) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(goPath + File.separator + "go", "build", "main.go");
        Map<String, String> environment = pb.environment();
        environment.put("GOOS", platform.goos());
        environment.put("GOARCH", platform.goarch());
        pb.directory(new File(output));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        int exitCode = pb.start().waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("打包失败，go build 退出码: " + exitCode);
        }
        System.out.println("打包完成。。。");
    }


    /**
     * 将指定的 JDK zip 复制到资源目录，供启动器运行时解压使用。
     *
     * @param to 加密产物输出目录
     * @throws Exception JDK zip 不存在或复制失败时抛出异常
     */
    private void addJdk(String to) throws Exception {
        Path sourcePath = Paths.get(jdkZip);    // 源ZIP文件路径
        Path targetPath = Paths.get(to, "resource", "jdk.zip"); // 目标路径
        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
    }
}
