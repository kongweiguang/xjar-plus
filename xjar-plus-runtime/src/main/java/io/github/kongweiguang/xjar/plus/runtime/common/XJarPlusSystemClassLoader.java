/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

/**
 * 将系统加载器的查找转交给已认证的应用加载器，供创建非继承线程的 API 使用。
 *
 * @author kongweiguang
 */
public final class XJarPlusSystemClassLoader extends ClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final ClassLoader launcherParent;
    private volatile PlainArchiveClassLoader applicationLoader;

    /**
     * 仅在绑定受保护归档前保留 JVM 提供的应用加载器，以完成 runtime bootstrap。
     */
    public XJarPlusSystemClassLoader(ClassLoader parent) {
        super(parent);
        if (parent == null) {
            throw new IllegalArgumentException("the JVM must provide its original application loader as parent");
        }
        this.launcherParent = parent;
    }

    /**
     * 保持 runtime 和 format 类的身份一致，因为二者共同启动已认证归档加载器。
     */
    private static boolean isSharedRuntimeClass(String name) {
        return name.startsWith("io.github.kongweiguang.xjar.plus.runtime.")
               || name.startsWith("io.github.kongweiguang.xjar.plus.format.");
    }

    /**
     * bootstrap 资源仍由原始加载器解析，避免 bridge 与应用加载器之间形成递归。
     */
    private static boolean isSharedRuntimeResource(String name) {
        return name.startsWith("io/github/kongweiguang/xjar/plus/runtime/")
               || name.startsWith("io/github/kongweiguang/xjar/plus/format/");
    }

    /**
     * 仅绑定一次，使系统加载器调用与已认证应用共享同一套类身份。
     */
    public synchronized void bind(PlainArchiveClassLoader loader) {
        if (loader == null) {
            throw new IllegalArgumentException("application loader must not be null");
        }
        if (applicationLoader != null) {
            throw new IllegalStateException("XJar Plus system loader is already bound");
        }
        if (loader.getParent() != applicationParent()) {
            throw new IllegalArgumentException("application loader must use the bridge's non-application parent");
        }
        applicationLoader = loader;
    }

    /**
     * 暴露原应用加载器的父加载器，避免应用加载器查找到受保护类的原始字节。
     */
    public ClassLoader applicationParent() {
        return launcherParent == null ? null : launcherParent.getParent();
    }

    /**
     * bridge 本身不定义类，因此不增加逐类监视器；类定义锁由实际拥有类的加载器提供。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 此处仅借用加载器视图；它只由启动器 shutdown hook 关闭，查找过程不得关闭。
        final ClassLoader application = applicationLoader;
        final Class<?> loaded;
        if (application != null && !isSharedRuntimeClass(name)) {
            loaded = application.loadClass(name);
        } else {
            loaded = launcherParent.loadClass(name);
        }
        if (resolve) {
            resolveClass(loaded);
        }
        return loaded;
    }

    /**
     * 应用资源使用认证视图；绑定前仍可访问启动器的父加载器。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public URL getResource(String name) {
        // 此处仅借用加载器视图；它只由启动器 shutdown hook 关闭，查找过程不得关闭。
        final ClassLoader application = applicationLoader;
        if (application != null && !isSharedRuntimeResource(name)) {
            return application.getResource(name);
        }
        return launcherParent.getResource(name);
    }

    /**
     * 按应用加载器的相同顺序枚举认证资源，不暴露系统加载器中的原始条目。
     */
    @SuppressWarnings("PMD.CloseResource")
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        // 此处仅借用加载器视图；它只由启动器 shutdown hook 关闭，查找过程不得关闭。
        final ClassLoader application = applicationLoader;
        if (application != null && !isSharedRuntimeResource(name)) {
            return application.getResources(name);
        }
        return launcherParent.getResources(name);
    }
}
