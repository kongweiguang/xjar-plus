/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.solon;

import io.github.kongweiguang.xjar.plus.runtime.common.ArchiveRuntime;
import org.noear.solon.loader.JarLauncher;
import org.noear.solon.loader.archive.Archive;

import java.net.URL;

/**
 * 认证原始 manifest 和索引后，复用 Solon 可执行 JAR 的路由和类路径发现流程。
 *
 * @author kongweiguang
 */
public final class XJarPlusSolonLauncher extends JarLauncher {
    private static final String LAUNCHER_CLASS = "io.github.kongweiguang.xjar.plus.runtime.solon.XJarPlusSolonLauncher";

    private ArchiveRuntime archive;

    /**
     * 避免调用方绕过 main 执行的归档认证而构造启动器。
     */
    private XJarPlusSolonLauncher() {
    }

    /**
     * 在 Solon 原生启动加载应用类或调用 Start-Class 前认证归档。
     */
    public static void main(String[] args) throws Exception {
        final XJarPlusSolonLauncher launcher = new XJarPlusSolonLauncher();
        launcher.archive = ArchiveRuntime.open(XJarPlusSolonLauncher.class, LAUNCHER_CLASS);
        launcher.launch(args);
    }

    /**
     * 提供已认证的原始应用类，不信任重写后的启动器 manifest。
     */
    @Override
    protected String getMainClass() {
        return archive.applicationMainClass();
    }

    /**
     * 替换 Solon 的加载器，同时保留归档、父加载器、类路径顺序和 TCCL 流程。
     */
    @Override
    protected ClassLoader createClassLoader(URL[] urls) throws Exception {
        final Archive nativeArchive = getArchive();
        return new XJarPlusSolonClassLoader(isExploded(), nativeArchive, urls,
                getClass().getClassLoader(), archive);
    }
}
