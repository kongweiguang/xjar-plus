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
import java.util.Map;
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
    private XAnyEntryFilter<JarArchiveEntry> includes = XKit.any();
    private XAllEntryFilter<JarArchiveEntry> excludes = XKit.all();
    private String jarArgs = "";
    private String jdkZip = "";
    private String goPath = null;
    private boolean pkgPlatform = false;

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
     * 指定密文包文件, 并执行加密.
     *
     * @param to 密文包文件
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

        //加密jar包
        Files.createDirectories(Paths.get(to, "resource"));
        XCryptos.encrypt(jar, new File(to, "resource" + File.separator + appName), key, filter);
        //生成启动器
        XGo.make(to, key, appName, jarArgs);
        //添加jdk
        addJdk(jdkZip, to);

        System.out.println("加密完成。。。");

        System.out.println("开始打包。。。");

        buildPKG(to);

        System.out.println("打包完成。。。");
    }

    private void buildPKG(String to) {
        if (goPath != null) {
            try {
                ProcessBuilder processBuilder = new ProcessBuilder(goPath + File.separator + "go", "build", "main.go");
                if (pkgPlatform) {
                    Map<String, String> environment = processBuilder.environment();
                    environment.put("GOOS", "linux");
                    environment.put("GOARCH", "amd64");
                }
                processBuilder.directory(new File(to));
                processBuilder.redirectErrorStream(true);
                Process start = processBuilder.start();
            } catch (IOException e) {
                System.out.println("打包可执行文件失败，没有配置go环境");
            }
        }
    }

    private void addJdk(String jdkZip, String to) {
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

}
