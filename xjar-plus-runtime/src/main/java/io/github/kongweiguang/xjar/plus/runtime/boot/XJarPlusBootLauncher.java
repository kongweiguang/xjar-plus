/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.boot;

import io.github.kongweiguang.xjar.plus.runtime.common.ArchiveRuntime;
import org.springframework.boot.loader.JarLauncher;

import java.net.URL;

/**
 * 保留 Boot classic 的归档发现流程，并将其类加载器替换为经过认证的适配器。
 *
 * @author kongweiguang
 */
public final class XJarPlusBootLauncher extends JarLauncher {
    private static final String LAUNCHER_CLASS = "io.github.kongweiguang.xjar.plus.runtime.boot.XJarPlusBootLauncher";

    private ArchiveRuntime archive;

    /**
     * 避免在 main 打开并认证 XJar Plus 控制记录前构造启动器。
     */
    private XJarPlusBootLauncher() {
    }

    /**
     * 在 Boot 开始加载应用类路径条目之前认证密钥信封、索引和原始 manifest。
     */
    public static void main(String[] args) throws Exception {
        final XJarPlusBootLauncher launcher = new XJarPlusBootLauncher();
        launcher.archive = ArchiveRuntime.open(XJarPlusBootLauncher.class, LAUNCHER_CLASS);
        launcher.launch(args);
    }

    /**
     * 从已认证的原始 manifest 中选择 Start-Class，而不依赖重写后的路由元数据。
     */
    @Override
    protected String getMainClass() {
        return archive.applicationMainClass();
    }

    /**
     * 保留 Boot 的嵌套 URL 列表和父加载器，只替换负责定义应用类的加载器。
     */
    @Override
    protected ClassLoader createClassLoader(URL[] urls) throws Exception {
        return new XJarPlusBootClassLoader(urls, getClass().getClassLoader(), archive);
    }
}
