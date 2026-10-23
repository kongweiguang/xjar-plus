/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import io.github.kongweiguang.xjar.plus.runtime.common.packagetest.PackageFixtureOne;
import io.github.kongweiguang.xjar.plus.runtime.common.packagetest.PackageFixtureTwo;
import io.github.kongweiguang.xjar.plus.runtime.common.packagetest.ParentPackageMarker;
import io.github.kongweiguang.xjar.plus.testfixture.concurrent.ConcurrentChild;
import io.github.kongweiguang.xjar.plus.testfixture.concurrent.ConcurrentSuperclass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证同包条目以及父加载器拥有同名包时的真实类定义行为。
 *
 * @author kongweiguang
 */
class PackageDefinitionRecoveryTest {
    private static final String FIXTURE_PACKAGE = "io.github.kongweiguang.xjar.plus.runtime.common.packagetest";
    private static final String CONCURRENT_PACKAGE = "io.github.kongweiguang.xjar.plus.testfixture.concurrent";
    private static final String ONE_PATH = FIXTURE_PACKAGE.replace('.', '/') + "/PackageFixtureOne.class";
    private static final String TWO_PATH = FIXTURE_PACKAGE.replace('.', '/') + "/PackageFixtureTwo.class";
    private static final String CONCURRENT_CHILD_PATH = CONCURRENT_PACKAGE.replace('.', '/') + "/ConcurrentChild.class";
    private static final String CONCURRENT_SUPERCLASS_PATH =
            CONCURRENT_PACKAGE.replace('.', '/') + "/ConcurrentSuperclass.class";
    private static final String ARTIFACT_ID = "00112233-4455-4677-8899-aabbccddeeff";

    /**
     * 将 fixture 类限制在子加载器中，确保测试覆盖真实加载器锁，而非父加载器委派。
     */
    private static ClassLoader isolatedConcurrentFixtureParent() {
        return new ClassLoader(PackageDefinitionRecoveryTest.class.getClassLoader()) {
            /**
     * 仅拒绝并发 fixture 包的父级副本，保留 runtime 的常规委派行为。
             */
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(CONCURRENT_PACKAGE + ".")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    /**
     * 使用已认证的明文 fixture 类构建加载器，以覆盖生产类定义路径。
     */
    private static PlainArchiveClassLoader newLoader(Path archivePath, ClassLoader parent) throws Exception {
        return newLoader(archivePath, parent, ONE_PATH, TWO_PATH, manifest());
    }

    /**
     * 为选定条目构建加载器，使并发回归与旧 fixture 相互隔离。
     */
    private static PlainArchiveClassLoader newLoader(Path archivePath, ClassLoader parent, String firstPath,
                                                     String secondPath, Manifest archiveManifest) throws Exception {
        final List<EntryMetadata> metadata = new ArrayList<EntryMetadata>();
        metadata.add(metadata(firstPath, classBytes(firstPath)));
        metadata.add(metadata(secondPath, classBytes(secondPath)));
        final Constructor<ArchiveRuntime> constructor = ArchiveRuntime.class.getDeclaredConstructor(String.class,
                byte[].class, ArchiveIndex.class, Manifest.class, URL.class);
        constructor.setAccessible(true);
        final ArchiveRuntime archive = constructor.newInstance(ARTIFACT_ID, new byte[32], new ArchiveIndex(metadata),
                archiveManifest, archivePath.toUri().toURL());
        return new PlainArchiveClassLoader(new URL[]{archivePath.toUri().toURL()}, parent, archive);
    }

    /**
     * 使用唯一 fixture 包创建受保护加载器，避免类定义状态在测试之间相互影响。
     */
    private static PlainArchiveClassLoader newConcurrentLoader(Path archivePath, ClassLoader parent) throws Exception {
        return newLoader(archivePath, parent, CONCURRENT_CHILD_PATH, CONCURRENT_SUPERCLASS_PATH,
                manifest(CONCURRENT_PACKAGE));
    }

    /**
     * 写入两个有效 class 文件和 sealed manifest，以检查包缓存及来源 sealing。
     */
    private static Path writeArchive(Path archivePath) throws IOException {
        return writeArchive(archivePath, ONE_PATH, TWO_PATH, manifest());
    }

    /**
     * 仅写入当前测试请求的 fixture，避免独立测试意外定义彼此的 class 文件。
     */
    private static Path writeArchive(Path archivePath, String firstPath, String secondPath, Manifest archiveManifest)
            throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archivePath), archiveManifest)) {
            writeEntry(output, firstPath);
            writeEntry(output, secondPath);
        }
        return archivePath;
    }

    /**
     * 为并发回归单独写入归档条目，同时保持旧包 fixture 不变。
     */
    private static Path writeConcurrentArchive(Path archivePath) throws IOException {
        return writeArchive(archivePath, CONCURRENT_CHILD_PATH, CONCURRENT_SUPERCLASS_PATH,
                manifest(CONCURRENT_PACKAGE));
    }

    /**
     * 逐字节复制已编译的 fixture，因为受保护归档必须包含有效 JVM class 文件。
     */
    private static void writeEntry(JarOutputStream output, String path) throws IOException {
        output.putNextEntry(new JarEntry(path));
        try (InputStream input = PackageDefinitionRecoveryTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IOException("Missing compiled package fixture: " + path);
            }
            final byte[] buffer = new byte[4096];
            int readCount;
            while ((readCount = input.read(buffer)) >= 0) {
                if (readCount > 0) {
                    output.write(buffer, 0, readCount);
                }
            }
        }
        output.closeEntry();
    }

    /**
     * 使用 Java 8 兼容的有界缓冲区读取 fixture 字节，不依赖 Java 8 之后新增的流辅助 API。
     */
    private static byte[] classBytes(String path) throws IOException {
        try (InputStream input = PackageDefinitionRecoveryTest.class.getClassLoader().getResourceAsStream(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IOException("Missing compiled package fixture: " + path);
            }
            final byte[] buffer = new byte[4096];
            int readCount;
            while ((readCount = input.read(buffer)) >= 0) {
                if (readCount > 0) {
                    output.write(buffer, 0, readCount);
                }
            }
            return output.toByteArray();
        }
    }

    /**
     * 提供已认证 manifest 中的包属性，使测试能发现意外复用父加载器包定义的情况。
     */
    private static Manifest manifest() {
        return manifest(FIXTURE_PACKAGE);
    }

    /**
     * 使用包专属元数据，使每个隔离 fixture 归档都认证自身的 sealing 契约。
     */
    private static Manifest manifest(String packageName) {
        final Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        final Attributes packageAttributes = new Attributes();
        packageAttributes.putValue(Attributes.Name.SPECIFICATION_TITLE.toString(), "Authenticated fixture package");
        packageAttributes.putValue(Attributes.Name.SEALED.toString(), "true");
        manifest.getEntries().put(packageName.replace('.', '/') + "/", packageAttributes);
        return manifest;
    }

    /**
     * 计算 runtime 接受明文 fixture 类所需的准确长度、CRC 和 SHA。
     */
    private static EntryMetadata metadata(String path, byte[] content) throws Exception {
        final CRC32 crc = new CRC32();
        crc.update(content);
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
        return new EntryMetadata(path, false, content.length, crc.getValue(), digest);
    }

    /**
     * 直接调用适配器的受保护查找，避免父加载器中的 fixture 副本掩盖子加载器包行为。
     */
    private static Class<?> invokeFindClass(PlainArchiveClassLoader loader, String className) throws Exception {
        final Method findClass = PlainArchiveClassLoader.class.getDeclaredMethod("findClass", String.class);
        findClass.setAccessible(true);
        return (Class<?>) findClass.invoke(loader, className);
    }

    /**
     * 获取加载器实际使用的包监视器，以便先排队查找，再同时放行。
     */
    private static Object packageLock(PlainArchiveClassLoader loader, String packageName) throws Exception {
        final Field locksField = PlainArchiveClassLoader.class.getDeclaredField("packageLocks");
        locksField.setAccessible(true);
        @SuppressWarnings("unchecked") final Map<String, Object> locks = (Map<String, Object>) locksField.get(loader);
        return locks.computeIfAbsent(packageName, ignored -> new Object());
    }

    /**
     * 收集工作线程的异步类加载结果，使有界 join 能报告失败且不挂住测试进程。
     */
    private static Thread loadingThread(PlainArchiveClassLoader loader, String className,
                                        AtomicReference<Class<?>> result, AtomicReference<Throwable> failure) {
        return new Thread(() -> {
            try {
                result.set(loader.loadClass(className));
            } catch (ClassNotFoundException | LinkageError | RuntimeException thrown) {
                failure.set(thrown);
            }
        }, "package-lock-test-" + className.substring(className.lastIndexOf('.') + 1));
    }

    /**
     * 开始另一类的查找前，确认两个加载器均已到达对应且被持有的包监视器。
     */
    private static void awaitBlockedOnMonitor(Thread thread, Object monitor) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            final ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
            if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor)) {
                return;
            }
            if (!thread.isAlive()) {
                throw new AssertionError("class-loading worker stopped before reaching package metadata lock");
            }
            Thread.sleep(5L);
        }
        throw new AssertionError("class-loading worker did not reach the package metadata lock in time");
    }

    /**
     * 释放 JDK 共享缓存中的归档句柄；元数据探测有意保留 URL 缓存策略。
     */
    private static void closeCachedArchive(Path archivePath, String entryPath) throws IOException {
        final URL resource = new URL("jar:" + archivePath.toUri().toURL() + "!/" + entryPath);
        final JarURLConnection connection = (JarURLConnection) resource.openConnection();
        connection.getJarFile().close();
    }

    /**
     * 加载两个分别认证的类，确保同一包定义能够服务于两次实际类定义。
     */
    @Test
    void loadsMultipleProtectedClassesFromOnePackage(@TempDir final Path tempDir) throws Exception {
        final Path archivePath = writeArchive(tempDir.resolve("same-package.jar"));
        try {
            try (PlainArchiveClassLoader loader = newLoader(archivePath, new ClassLoader(null) {
            })) {
                final Class<?> first = invokeFindClass(loader, PackageFixtureOne.class.getName());
                final Class<?> second = invokeFindClass(loader, PackageFixtureTwo.class.getName());

                assertSame(loader, first.getClassLoader());
                assertSame(loader, second.getClassLoader());
                assertSame(first.getPackage(), second.getPackage());
                assertTrue(first.getPackage().isSealed(archivePath.toUri().toURL()));
                assertEquals("Authenticated fixture package", first.getPackage().getSpecificationTitle());
            }
        } finally {
            closeCachedArchive(archivePath, ONE_PATH);
        }
    }

    /**
     * 确保祖先加载器中的同名包不会被误认为本地包，也不会被用于子加载器的元数据。
     */
    @Test
    void definesChildPackageWhenParentAlreadyOwnsTheSameName(@TempDir final Path tempDir) throws Exception {
        final ClassLoader parent = ParentPackageMarker.class.getClassLoader();
        final Package parentPackage = ParentPackageMarker.class.getPackage();
        assertTrue(parentPackage != null, "the marker must establish the parent package before child loading");

        final Path archivePath = writeArchive(tempDir.resolve("parent-package.jar"));
        try {
            try (PlainArchiveClassLoader loader = newLoader(archivePath, parent)) {
                final Class<?> first = invokeFindClass(loader, PackageFixtureOne.class.getName());
                final Class<?> second = invokeFindClass(loader, PackageFixtureTwo.class.getName());
                final Package childPackage = first.getPackage();

                assertSame(loader, first.getClassLoader());
                assertSame(loader, second.getClassLoader());
                assertSame(first.getPackage(), second.getPackage());
                assertNotSame(parentPackage, childPackage);
                assertTrue(childPackage.isSealed(archivePath.toUri().toURL()));
                assertEquals("Authenticated fixture package", childPackage.getSpecificationTitle());
            }
        } finally {
            closeCachedArchive(archivePath, ONE_PATH);
        }
    }

    /**
     * 让子类和父类查找竞争包元数据，证明包锁不会覆盖 defineClass 的依赖解析过程。
     */
    @Test
    void loadsSamePackageSuperclassConcurrentlyWithoutHoldingPackageLock(
            @TempDir final Path tempDir) throws Exception {
        final Path archivePath = writeConcurrentArchive(tempDir.resolve("concurrent-superclass.jar"));
        final PlainArchiveClassLoader loader = newConcurrentLoader(archivePath, isolatedConcurrentFixtureParent());
        final Object packageLock = packageLock(loader, CONCURRENT_PACKAGE);
        final AtomicReference<Class<?>> childClass = new AtomicReference<Class<?>>();
        final AtomicReference<Class<?>> baseClass = new AtomicReference<Class<?>>();
        final AtomicReference<Throwable> childFailure = new AtomicReference<Throwable>();
        final AtomicReference<Throwable> baseFailure = new AtomicReference<Throwable>();
        final Thread childThread = loadingThread(loader, ConcurrentChild.class.getName(), childClass, childFailure);
        final Thread baseThread = loadingThread(loader, ConcurrentSuperclass.class.getName(), baseClass, baseFailure);
        childThread.setDaemon(true);
        baseThread.setDaemon(true);
        try (PlainArchiveClassLoader ignored = loader) {
            try {
                synchronized (packageLock) {
                    childThread.start();
                    awaitBlockedOnMonitor(childThread, packageLock);
                    baseThread.start();
                    awaitBlockedOnMonitor(baseThread, packageLock);
                }
                childThread.join(TimeUnit.SECONDS.toMillis(5));
                baseThread.join(TimeUnit.SECONDS.toMillis(5));
                assertFalse(childThread.isAlive(),
                        "child definition must not deadlock on its same-package superclass");
                assertFalse(baseThread.isAlive(), "superclass definition must leave the package metadata monitor");
                assertNull(childFailure.get(), "child class loading must complete without a linkage failure");
                assertNull(baseFailure.get(), "base class loading must complete without a linkage failure");
                assertSame(loader, childClass.get().getClassLoader());
                assertSame(loader, baseClass.get().getClassLoader());
                assertSame(baseClass.get(), childClass.get().getSuperclass());
            } finally {
                childThread.interrupt();
                baseThread.interrupt();
                childThread.join(100L);
                baseThread.join(100L);
            }
        } finally {
            closeCachedArchive(archivePath, CONCURRENT_CHILD_PATH);
        }
    }
}
