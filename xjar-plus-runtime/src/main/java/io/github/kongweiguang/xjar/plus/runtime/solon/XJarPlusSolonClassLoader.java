/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.solon;

import io.github.kongweiguang.xjar.plus.runtime.common.ArchiveRuntime;
import io.github.kongweiguang.xjar.plus.runtime.common.ClassLoaderSupport;
import org.noear.solon.loader.LaunchedURLClassLoader;
import org.noear.solon.loader.archive.Archive;

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
 * 保留 Solon 的嵌套归档加载器，同时通过索引认证类和资源。
 *
 * @author kongweiguang
 */
public final class XJarPlusSolonClassLoader extends LaunchedURLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final ArchiveRuntime archive;
    private final Map<String, Package> localPackages = new ConcurrentHashMap<String, Package>();
    private final Map<String, Object> packageLocks = new ConcurrentHashMap<String, Object>();

    /**
     * 使用 Solon 感知归档的构造方式，使嵌套 URL 所有权和类路径缓存继续由其原生机制管理。
     */
    public XJarPlusSolonClassLoader(boolean exploded, Archive nativeArchive, URL[] urls,
                                    ClassLoader parent, ArchiveRuntime archive) {
        super(exploded, nativeArchive, urls, parent);
        this.archive = archive;
    }

    /**
     * 派生包锁标识，使共享包属性的类彼此串行化，而不影响其他包。
     */
    private static String packageName(String className) {
        final int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot);
    }

    /**
     * 包装父加载器优先查找结果，因为 Solon 启动器的父加载器可能将受保护条目作为原始资源暴露。
     */
    @Override
    public URL getResource(String name) {
        return archive.wrap(super.getResource(name), name);
    }

    /**
     * 保留 Solon 聚合类路径的顺序，并一致认证父加载器和嵌套归档返回的结果。
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
     * 包装所有索引资源，使明文条目也必须符合已认证的长度、CRC 和摘要信息。
     */
    @Override
    public URL findResource(String name) {
        return archive.wrap(super.findResource(name), name);
    }

    /**
     * 保留 Solon 嵌套归档顺序，同时保留每个匹配 URL 的物理标识。
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
     * 仅在条目完整校验后定义受保护类，真正的外部依赖仍由原生机制加载。
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
     * 定义或复用包元数据前，依据实际来源 URL 检查 sealing 规则。
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
     * 记录 Solon 原生 URL 加载器创建的包状态，供后续 sealing 检查使用。
     */
    private void recordPackage(String packageName) {
        if (!packageName.isEmpty()) {
            final Object lock = packageLocks.computeIfAbsent(packageName, ignored -> new Object());
            synchronized (lock) {
                // Solon 的原生类路径会先定义本地包，再返回直通类。
                final Package defined = getPackage(packageName);
                if (defined != null) {
                    localPackages.putIfAbsent(packageName, defined);
                }
            }
        }
    }
}
