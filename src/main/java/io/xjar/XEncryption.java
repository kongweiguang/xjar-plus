package io.xjar;

import io.xjar.filter.XAllEntryFilter;
import io.xjar.filter.XAnyEntryFilter;
import io.xjar.filter.XMixEntryFilter;
import io.xjar.key.XKey;
import org.apache.commons.compress.archivers.jar.JarArchiveEntry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.xjar.XFilters.*;

/**
 * 加密
 *
 * @author Payne 646742615@qq.com
 * 2020/4/30 17:05
 */
public class XEncryption {
    private File jar;
    private XKey key;
    private final XAnyEntryFilter<JarArchiveEntry> includes = XKit.any();
    private final XAllEntryFilter<JarArchiveEntry> excludes = XKit.all();
    private String jarArgs = "";
    private String jdkZip = "";
    private String goPath = null;
    private boolean pkgPlatform = false;
    private String validStartDate = "";
    private String validEndDate = "";
    private String code = UUID.randomUUID().toString();

    /**
     * 指定原文包路径
     *
     * @param jar 原文包路径
     * @return {@code this}
     */
    public XEncryption from(String jar) {
        return from(new File(jar));
    }

    /**
     * 指定原文包文件
     *
     * @param jar 原文包文件
     * @return {@code this}
     */
    public XEncryption from(File jar) {
        this.jar = jar;
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
     * @param to      密文包文件路径
     * @param appName 应用名称
     * @throws Exception 加密异常
     */
    public void to(String to, String appName) throws Exception {
        if (jar == null) {
            throw new IllegalArgumentException("jar to encrypt is null. [please call from(String jar) or from(File jar) before]");
        }

        if (key == null) {
            throw new IllegalArgumentException("key to encrypt is null. [please call use(String password) or use(String algorithm, int keysize, int ivsize, String password) before]");
        }

        XMixEntryFilter<JarArchiveEntry> filter;
        if (includes.size() == 0 && excludes.size() == 0) {
            filter = null;
        } else {
            filter = XKit.all();
            if (includes.size() > 0) {
                filter.mix(includes);
            }
            if (excludes.size() > 0) {
                filter.mix(excludes);
            }
        }

        if (null == appName) {
            appName = "app.jar";
        }

        System.out.println("开始加密。。。");

        //加密jar包
        Files.createDirectories(Paths.get(to, "resource"));
        XCryptos.encrypt(jar, Paths.get(to, "resource", appName).toFile(), key, filter);

        if (Objects.equals("", validStartDate)) {
            this.validStartDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }

        //生成启动器
        XGo.make(to, key, appName, jarArgs, validStartDate, validEndDate, code);
        //添加jdk
        addJdk(to);

        System.out.println("加密完成。。。");

        System.out.println("开始打包。。。");

        buildPKG(to);
    }

    private void buildPKG(String to) {
        if (goPath != null) {
            try {
                getProcess(to)
                        .onExit()
                        .handle((p, t) -> {
                            if (t != null) {
                                System.err.println("打包异常" + t.getMessage());
                            } else {
                                System.out.println("打包完成。。。");
                            }

                            return p.exitValue();
                        }).join();
            } catch (IOException e) {
                System.out.println("打包可执行文件失败，调用go环境打包失败");
            }
        }
    }

    private Process getProcess(String to) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(goPath + File.separator + "go", "build", "main.go");
        if (pkgPlatform) {
            Map<String, String> environment = pb.environment();
            environment.put("GOOS", "linux");
            environment.put("GOARCH", "amd64");
        }
        pb.directory(new File(to));
        pb.redirectErrorStream(true);
        return pb.start();
    }

    private void addJdk(String to) {
        if (jdkZip == null || jdkZip.isEmpty()) {
            return;
        }

        try {
            Path sourcePath = Paths.get(jdkZip);    // 源ZIP文件路径
            Path targetPath = Paths.get(to, "resource", "jdk.zip"); // 目标路径
            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public XEncryption jarArgs(String jarArgs) {
        this.jarArgs = jarArgs;
        return this;
    }

    public XEncryption jdkZip(String jdkZip) {
        this.jdkZip = jdkZip;
        return this;
    }

    public XEncryption pkgLinux() {
        this.pkgPlatform = true;
        return this;
    }

    public XEncryption goPath(String goPath) {
        this.goPath = goPath;
        return this;
    }

    public XEncryption validStartDate(String date) {
        this.validStartDate = date;
        return this;
    }

    public XEncryption validEndDate(String date) {
        this.validEndDate = date;
        return this;
    }

    public XEncryption code(String code) {
        this.code = code;
        return this;
    }
}
