/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime;

import io.github.kongweiguang.xjar.plus.runtime.common.ArchiveRuntime;
import io.github.kongweiguang.xjar.plus.runtime.common.PlainArchiveClassLoader;
import io.github.kongweiguang.xjar.plus.runtime.common.XJarPlusSystemClassLoader;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 普通可执行 JAR 的入口；先认证控制文件，再加载应用主类。
 *
 * @author kongweiguang
 */
public final class XJarPlusLauncher {
    private static final String LAUNCHER_CLASS = "io.github.kongweiguang.xjar.plus.runtime.XJarPlusLauncher";
    private static final String SYSTEM_LOADER_PROPERTY = "java.system.class.loader";
    private static final String WINDOWS_ARGV_PROPERTY = "jdk.lang.Process.allowAmbiguousCommands";
    private static final String SYSTEM_LOADER_CLASS =
            "io.github.kongweiguang.xjar.plus.runtime.common.XJarPlusSystemClassLoader";

    /**
     * 避免意外实例化；启动器状态仅属于一次 JVM 入口调用。
     */
    private XJarPlusLauncher() {
    }

    /**
     * 通过已认证的系统加载器 bridge 重启一次，因为新版 JDK 工作线程使用系统 TCCL。
     */
    public static void main(String[] args) throws Throwable {
        final ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (!(systemLoader instanceof XJarPlusSystemClassLoader)) {
            System.exit(relaunchWithSystemLoader(args));
            return;
        }
        final ArchiveRuntime archive = ArchiveRuntime.open(XJarPlusLauncher.class, LAUNCHER_CLASS);
        final String mainClassName = archive.applicationMainClass();
        final URL[] urls = new URL[]{archiveLocation(XJarPlusLauncher.class)};
        final XJarPlusSystemClassLoader bridge = (XJarPlusSystemClassLoader) systemLoader;
        final ClassLoader parent = bridge.applicationParent();
        // 由 shutdown hook 负责关闭，使应用派生线程在进程结束前仍可正常加载类。
        final PlainArchiveClassLoader applicationLoader = new PlainArchiveClassLoader(urls, parent, archive); // NOPMD
        bridge.bind(applicationLoader);
        registerCloseOnShutdown(applicationLoader);
        Thread.currentThread().setContextClassLoader(applicationLoader);
        invokeMain(applicationLoader, mainClassName, args);
    }

    /**
     * 在解析密钥前重新执行，使子进程启用系统加载器且不消耗 broker 状态。
     */
    private static int relaunchWithSystemLoader(String[] applicationArguments) throws Exception {
        final String configuredLoader = System.getProperty(SYSTEM_LOADER_PROPERTY);
        if (configuredLoader != null && !SYSTEM_LOADER_CLASS.equals(configuredLoader)) {
            throw new IOException("A different system class loader is configured; start directly with -D"
                                  + SYSTEM_LOADER_PROPERTY + "=" + SYSTEM_LOADER_CLASS
                                  + " and ensure any custom loader or agent is compatible with the bridge");
        }
        final String environmentIssue = unsupportedRelaunchEnvironment(System.getenv());
        if (environmentIssue != null) {
            throw new IOException("Automatic protected-JAR restart cannot safely repeat JVM option category "
                                  + environmentIssue + "; start directly with -D" + SYSTEM_LOADER_PROPERTY + "="
                                  + SYSTEM_LOADER_CLASS
                                  + " and ensure any custom loader or agent is compatible with the bridge");
        }
        final List<String> command = new ArrayList<String>();
        command.add(javaExecutable());
        for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (argument.startsWith("-D" + SYSTEM_LOADER_PROPERTY + "=")) {
                continue;
            }
            final String unsupported = unsupportedRelaunchOption(argument);
            if (unsupported != null) {
                throw new IOException("Automatic protected-JAR restart cannot safely repeat JVM option " + unsupported
                                      + "; start directly with -D" + SYSTEM_LOADER_PROPERTY + "=" + SYSTEM_LOADER_CLASS
                                      + " and ensure any custom loader or agent is compatible with the bridge");
            }
            command.add(argument);
        }
        command.add("-D" + SYSTEM_LOADER_PROPERTY + "=" + SYSTEM_LOADER_CLASS);
        command.add("-jar");
        command.add(new File(archiveLocation(XJarPlusLauncher.class).toURI()).getAbsolutePath());
        for (String argument : applicationArguments) {
            command.add(argument);
        }
        return runChild(command);
    }

    /**
     * 使用当前 JDK 的可执行文件，确保一次性重启保留原 Java 版本和安装位置。
     */
    private static String javaExecutable() throws IOException {
        final String executableName = File.separatorChar == '\\' ? "java.exe" : "java";
        final File executable = Paths.get(System.getProperty("java.home"), "bin", executableName).toFile();
        if (!executable.isFile()) {
            throw new IOException("Unable to locate the current Java executable: " + executable);
        }
        return executable.getAbsolutePath();
    }

    /**
     * 拒绝会重复录制、加载 agent 或写入固定文件且无法透明重放的 JVM 选项。
     */
    static String unsupportedRelaunchOption(String argument) {
        final String option = argument.toLowerCase(Locale.ROOT);
        if (startsWithAny(option, "-javaagent:")) {
            return "Java agent";
        }
        if (startsWithAny(option, "-agentlib:", "-agentpath:", "-xrun")) {
            return "native JVM agent";
        }
        if (startsWithAny(option, "-xx:startflightrecording", "-xx:flightrecorderoptions")) {
            return "flight recording";
        }
        if (startsWithAny(option, "-xx:heapdumppath=")) {
            return "heap-dump output file";
        }
        if (isDiagnosticOutputFile(option)) {
            return "JVM diagnostic output file";
        }
        if (startsWithAny(option, "-xx:onerror=", "-xx:onoutofmemoryerror=")) {
            return "JVM failure action";
        }
        if (startsWithAny(option, "-dcom.sun.management.jmxremote.port",
                "-dcom.sun.management.jmxremote.rmi.port", "-dcom.sun.management.config.file")) {
            return "fixed-port JMX configuration";
        }
        return null;
    }

    /**
     * 仅匹配少量明确列出的前缀，使类别判断边界清晰且便于审查。
     */
    private static boolean startsWithAny(String option, String... prefixes) {
        for (String prefix : prefixes) {
            if (option.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单独识别诊断输出路径；统一日志仅在指定文件目标时才会产生重放风险。
     */
    private static boolean isDiagnosticOutputFile(String option) {
        return startsWithAny(option, "-xx:errorfile=", "-xx:logfile=", "-xx:dumploadedclasslist=",
                "-xx:archiveclassesatexit=", "-xx:perfdatasavetofile=", "-xloggc:")
               || option.startsWith("-xlog:") && option.contains("file=");
    }

    /**
     * 仅当继承的 JVM 选项通道会重复副作用时才拒绝，同时不暴露选项内容。
     */
    static String unsupportedRelaunchEnvironment(Map<String, String> environment) {
        for (String variable : new String[]{"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}) {
            final String options = environment.get(variable);
            if (options == null || options.trim().isEmpty()) {
                continue;
            }
            final String normalized = options.replace('"', ' ').replace('\'', ' ');
            for (String token : normalized.trim().split("\\s+")) {
                final String category = unsupportedRelaunchOption(token);
                if (category != null) {
                    return category + " injected by " + variable;
                }
            }
        }
        return null;
    }

    /**
     * 继承应用输入输出流，并在包装进程取消或中断时终止子进程。
     */
    private static int runChild(List<String> command) throws IOException {
        final Process child = startChildProcess(new ProcessBuilder(command).inheritIO());
        final Thread shutdownHook = new Thread(() -> stopChild(child), "xjar-plus-relaunch-stop");
        boolean hookInstalled = false;
        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            hookInstalled = true;
            return child.waitFor();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the protected application", interrupted);
        } finally {
            if (child.isAlive()) {
                stopChild(child);
            }
            if (hookInstalled) {
                removeShutdownHookAfterChildExit(shutdownHook);
            }
        }
    }

    /**
     * 仅在创建 bootstrap 子进程时临时启用 JDK 严格的 Windows argv 引号转义。
     */
    static Process startChildProcess(ProcessBuilder builder) throws IOException {
        if (File.separatorChar != '\\') {
            return builder.start();
        }
        final String originalValue = System.getProperty(WINDOWS_ARGV_PROPERTY);
        try {
            System.setProperty(WINDOWS_ARGV_PROPERTY, "false");
            return builder.start();
        } finally {
            if (originalValue == null) {
                System.clearProperty(WINDOWS_ARGV_PROPERTY);
            } else {
                System.setProperty(WINDOWS_ARGV_PROPERTY, originalValue);
            }
        }
    }

    /**
     * 仅在正常结束时移除 hook；JVM 正在关闭时，hook 已由运行时接管。
     */
    @SuppressWarnings("PMD.EmptyCatchBlock")
    private static void removeShutdownHookAfterChildExit(Thread shutdownHook) {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException shutdownStarted) {
            // JVM 关闭流程已接管此 hook，此时没有安全的后续操作。
        }
    }

    /**
     * 先给子进程短暂的优雅退出时间，超时后再强制结束其 JVM。
     */
    private static void stopChild(Process child) {
        if (!child.isAlive()) {
            return;
        }
        child.destroy();
        try {
            if (!child.waitFor(5L, TimeUnit.SECONDS)) {
                child.destroyForcibly();
                child.waitFor(5L, TimeUnit.SECONDS);
            }
        } catch (InterruptedException interrupted) {
            child.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 保持应用加载器可供后台线程使用，并在 JVM 关闭时释放其 JAR 句柄。
     */
    private static void registerCloseOnShutdown(final PlainArchiveClassLoader loader) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                loader.close();
            } catch (IOException ignored) {
            // JVM 已进入关闭流程，应用代码无法从此次关闭失败中恢复。
            }
        }, "xjar-plus-loader-close"));
    }

    /**
     * 从代码来源定位输入 JAR，使 manifest Class-Path 仍由 URLClassLoader 管理。
     */
    private static URL archiveLocation(Class<?> launcherClass) throws Exception {
        final ProtectionDomain domain = launcherClass.getProtectionDomain();
        final CodeSource source = domain == null ? null : domain.getCodeSource();
        final URL location = source == null ? null : source.getLocation();
        if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
            throw new IllegalStateException("Unable to locate the executable XJar Plus archive");
        }
        final File archive = new File(location.toURI());
        if (!archive.isFile()) {
            throw new IllegalStateException("XJar Plus runtime requires a packaged JAR archive");
        }
        return archive.toURI().toURL();
    }

    /**
     * 调用已认证的主类，并解开反射异常以保留应用原有的退出行为。
     */
    private static void invokeMain(ClassLoader loader, String className, String[] args) throws Throwable {
        final Class<?> mainClass = loader.loadClass(className);
        final Method main = mainClass.getMethod("main", String[].class);
        if (!Modifier.isStatic(main.getModifiers())) {
            throw new NoSuchMethodException("Application Main-Class method must be static: " + className);
        }
        try {
            main.invoke(null, new Object[]{args});
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
