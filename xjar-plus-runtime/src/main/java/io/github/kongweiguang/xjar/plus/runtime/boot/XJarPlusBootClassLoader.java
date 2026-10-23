/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.boot;

import io.github.kongweiguang.xjar.plus.runtime.common.ArchiveRuntime;
import io.github.kongweiguang.xjar.plus.runtime.common.ClassLoaderSupport;
import org.springframework.boot.loader.LaunchedURLClassLoader;

import java.io.IOException;
import java.net.URL;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 接入 Boot classic 自带的启动加载器，以保留其嵌套归档 URL 和类路径顺序。
 *
 * @author kongweiguang
 */
public final class XJarPlusBootClassLoader extends LaunchedURLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final ArchiveRuntime archive;
    private final Map<String, Package> localPackages = new ConcurrentHashMap<String, Package>();
    private final Map<String, Object> packageLocks = new ConcurrentHashMap<String, Object>();

    /**
     * 复用 Boot 原有加载器类型，仅包装已认证外层索引选中的资源。
     */
    public XJarPlusBootClassLoader(URL[] urls, ClassLoader parent, ArchiveRuntime archive) {
        super(urls, parent);
        this.archive = archive;
    }

    /**
     * 计算包锁标识，避免并行类定义同时修改同一包的元数据。
     */
    private static String packageName(String className) {
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    /**
     * 包装父加载器优先查找结果，因为 Boot 的父加载器可能暴露受保护归档的原始字节。
     */
    @Override
    public URL getResource(String name) {
        return archive.wrap(super.getResource(name), name);
    }

    /**
     * 保留 Boot 的资源聚合顺序，并认证父加载器和启动加载器两条路径返回的资源。
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
     * 明文资源继续由 Boot 原生处理器提供，密文资源则使用经过认证的 URL 视图。
     */
    @Override
    public URL findResource(String name) {
        return archive.wrap(super.findResource(name), name);
    }

    /**
     * 保留 Boot 的资源顺序，并以各自的归档标识暴露每个加密的重复资源。
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
     * 仅在完成校验后定义索引中的类，并继续使用 Boot 加载器处理外部依赖。
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
     * 先从已校验的源 manifest 定义包元数据，再应用归档的 sealing URL。
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
     * 仅串行化包元数据变更，使类校验过程仍可加载同包依赖。
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
     * 记录 Boot 原生 URL 加载器创建的包状态，使后续类定义继续遵守归档 sealing 规则。
     */
    private void recordPackage(String packageName) {
        if (!packageName.isEmpty()) {
            final Object lock = packageLocks.computeIfAbsent(packageName, ignored -> new Object());
            synchronized (lock) {
                // Boot 的原生 findClass 会先定义本地包，再返回直通类。
                final Package defined = getPackage(packageName);
                if (defined != null) {
                    localPackages.putIfAbsent(packageName, defined);
                }
            }
        }
    }
}
