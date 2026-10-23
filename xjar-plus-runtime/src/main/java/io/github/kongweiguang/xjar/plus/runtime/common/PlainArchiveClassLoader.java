/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 以 child-first 方式加载普通 JAR 应用，避免 JVM 自带启动器在认证前解析密文。
 *
 * @author kongweiguang
 */
public final class PlainArchiveClassLoader extends URLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final ArchiveRuntime archive;
    private final Map<String, Package> localPackages = new ConcurrentHashMap<String, Package>();
    private final Map<String, Object> packageLocks = new ConcurrentHashMap<String, Object>();

    /**
     * 以 platform loader 为父加载器，避免系统应用加载器暴露原始密文条目。
     */
    public PlainArchiveClassLoader(URL[] urls, ClassLoader parent, ArchiveRuntime archive) {
        super(urls, parent);
        this.archive = archive;
    }

    /**
     * 预先计算包锁标识，避免同一包中的类并发修改 sealing 元数据。
     */
    private static String packageName(String className) {
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    /**
     * 标识必须与启动加载器共享的 runtime 包，避免将其作为应用代码重复加载。
     */
    private static boolean isBootstrapPackage(String className) {
        return className.startsWith("io.github.kongweiguang.xjar.plus.runtime.")
               || className.startsWith("io.github.kongweiguang.xjar.plus.format.");
    }

    /**
     * 重新检查父加载器优先查找结果，因为系统加载器可能暴露受保护归档中的原始密文 URL。
     */
    @Override
    public URL getResource(String name) {
        return archive.wrap(super.getResource(name), name);
    }

    /**
     * 对父加载器优先和本地查找结果应用认证视图，同时保留原有枚举顺序。
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        final Enumeration<URL> resources = super.getResources(name);
        final ArrayList<URL> wrapped = new ArrayList<URL>();
        while (resources.hasMoreElements()) {
            wrapped.add(archive.wrap(resources.nextElement(), name));
        }
        return Collections.enumeration(wrapped);
    }

    /**
     * 从启动器加载器共享 bootstrap 包，同时让应用类位于 platform parent 之后。
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (isBootstrapPackage(name)) {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    try {
                        loaded = ClassLoader.getSystemClassLoader().loadClass(name);
                    } catch (ClassNotFoundException missingFromLauncher) {
                        loaded = findClass(name);
                    }
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
        return super.loadClass(name, resolve);
    }

    /**
     * 仅包装认证索引中标记为加密的资源，普通 JAR URL 保持不变。
     */
    @Override
    public URL findResource(String name) {
        return archive.wrap(super.findResource(name), name);
    }

    /**
     * 保留 URLClassLoader 的搜索顺序，并为所有匹配资源应用相同的认证视图。
     */
    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        final Enumeration<URL> resources = super.findResources(name);
        final ArrayList<URL> wrapped = new ArrayList<URL>();
        while (resources.hasMoreElements()) {
            wrapped.add(archive.wrap(resources.nextElement(), name));
        }
        return Collections.enumeration(wrapped);
    }

    /**
     * 完整性校验通过后定义索引中的类，外部依赖仍交由 URLClassLoader 加载。
     */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        final String packageName = packageName(name);
        final String path = name.replace('.', '/') + ".class";
        final URL resource = super.findResource(path);
        if (resource == null) {
            throw new ClassNotFoundException(name);
        }
        final ArchiveRuntime.ClassBytes classBytes;
        try {
            classBytes = archive.classBytes(resource, path);
        } catch (IOException failure) {
            throw new ClassNotFoundException(name, failure);
        }
        if (classBytes == null) {
            final Class<?> loaded = super.findClass(name);
            recordPackage(packageName);
            return loaded;
        }
        definePackageIfNecessary(name, classBytes);
        final byte[] bytes = classBytes.bytes();
        final CodeSource source = new CodeSource(classBytes.codeSource(), (CodeSigner[]) null);
        return defineClass(name, bytes, 0, bytes.length, source);
    }

    /**
     * 将类加入 sealed package 前，先从已认证的源 manifest 定义包元数据。
     */
    private void definePackageIfNecessary(String className, ArchiveRuntime.ClassBytes classBytes)
            throws ClassNotFoundException {
        final int dot = className.lastIndexOf('.');
        if (dot < 0) {
            return;
        }
        final String packageName = className.substring(0, dot);
        final Object lock = packageLocks.computeIfAbsent(packageName, ignored -> new Object());
        synchronized (lock) {
            defineOrVerifyPackage(packageName, classBytes);
        }
    }

    /**
     * 仅串行化包元数据变更，使类定义过程仍可安全解析同包依赖。
     */
    private void defineOrVerifyPackage(String packageName, ArchiveRuntime.ClassBytes classBytes)
            throws ClassNotFoundException {
        final ClassLoaderSupport.PackageMetadata metadata = ClassLoaderSupport.packageMetadata(
                classBytes.manifest(), packageName);
        final Package existing = localPackages.get(packageName);
        if (existing != null) {
            try {
                ClassLoaderSupport.verifyPackageSealing(existing, metadata.sealed(), classBytes.codeSource());
            } catch (SecurityException failure) {
                throw new ClassNotFoundException("Unable to define package " + packageName, failure);
            }
            return;
        }
        final URL sealBase = metadata.sealed() ? classBytes.codeSource() : null;
        try {
            final Package defined = definePackage(packageName, metadata.specificationTitle(),
                    metadata.specificationVersion(),
                    metadata.specificationVendor(), metadata.implementationTitle(),
                    metadata.implementationVersion(), metadata.implementationVendor(), sealBase);
            localPackages.put(packageName, defined);
        } catch (IllegalArgumentException concurrentDefinition) {
            Package raced = localPackages.get(packageName);
            if (raced == null) {
                // definePackage 仅会因本加载器已拥有该包而失败；祖先加载器中的同名包不冲突。
                raced = getPackage(packageName);
                if (raced != null) {
                    final Package cached = localPackages.putIfAbsent(packageName, raced);
                    raced = cached == null ? raced : cached;
                }
            }
            if (raced == null) {
                throw new ClassNotFoundException("Unable to define package " + packageName, concurrentDefinition);
            }
            try {
                ClassLoaderSupport.verifyPackageSealing(raced, metadata.sealed(), classBytes.codeSource());
            } catch (SecurityException failure) {
                throw new ClassNotFoundException("Unable to define package " + packageName, failure);
            }
        }
    }

    /**
     * 记录 URLClassLoader 原生创建的包状态，使后续索引类遵守相同的 sealing 来源。
     */
    private void recordPackage(String packageName) {
        if (!packageName.isEmpty()) {
            final Object lock = packageLocks.computeIfAbsent(packageName, ignored -> new Object());
            synchronized (lock) {
                // URLClassLoader 会先定义本地包，再返回直通类。
                final Package defined = getPackage(packageName);
                if (defined != null) {
                    localPackages.putIfAbsent(packageName, defined);
                }
            }
        }
    }
}
