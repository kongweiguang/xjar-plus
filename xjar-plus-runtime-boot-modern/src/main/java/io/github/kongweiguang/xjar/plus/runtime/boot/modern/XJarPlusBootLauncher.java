/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.boot.modern;

import io.github.kongweiguang.xjar.plus.runtime.common.ArchiveRuntime;
import org.springframework.boot.loader.launch.JarLauncher;

import java.net.URL;
import java.util.Collection;

/**
 * 遵循 Boot 现代启动器契约，使 3.2+ 嵌套归档保留其新协议和类路径语义。
 *
 * @author kongweiguang
 */
public final class XJarPlusBootLauncher extends JarLauncher {
    private static final String LAUNCHER_CLASS =
            "io.github.kongweiguang.xjar.plus.runtime.boot.modern.XJarPlusBootLauncher";

    private ArchiveRuntime archive;

    /**
     * 避免在加密索引和原始 manifest 完成认证前构造启动器。
     */
    private XJarPlusBootLauncher() throws Exception {
    }

    /**
     * 在 Boot 创建类加载器或尝试加载应用类之前打开并校验归档。
     */
    public static void main(String[] args) throws Exception {
        final XJarPlusBootLauncher launcher = new XJarPlusBootLauncher();
        launcher.archive = ArchiveRuntime.open(XJarPlusBootLauncher.class, LAUNCHER_CLASS);
        launcher.launch(args);
    }

    /**
     * 使用经过认证的 Start-Class，而不信任可变的外层 manifest 路由信息。
     */
    @Override
    protected String getMainClass() {
        return archive.applicationMainClass();
    }

    /**
     * 接管 Boot 新增的 Collection 扩展点，因为 3.2 已用它替代 classic URL 数组加载器 API。
     */
    @Override
    protected ClassLoader createClassLoader(Collection<URL> urls) throws Exception {
        final URL[] classPath = urls.toArray(new URL[0]);
        return new XJarPlusBootClassLoader(isExploded(), classPath, getClass().getClassLoader(), archive);
    }
}
