package io.github.kongweiguang.xjar.plus.integration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 显式选择目标 JVM 运行产物，不能用测试工具自身的 JVM 证明目标 JDK 兼容性。
 *
 * @author kongweiguang
 */
final class ExternalJava {
    private static final String WINDOWS_ARG_PROPERTY = "jdk.lang.Process.allowAmbiguousCommands";
    private static final Object WINDOWS_PROCESS_START_LOCK = new Object();

    /**
     * 进程操作均为独立静态测试辅助方法，不允许创建无意义的实例。
     */
    private ExternalJava() {
    }

    /**
     * 从持续集成参数定位指定主版本的 JVM，并在探测成功或失败后都回收子进程。
     */
    static Path executable(int major) throws IOException, InterruptedException {
        String property = System.getProperty("xjp.java" + major);
        Path java = property == null || property.isBlank()
                ? Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                : Path.of(property).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(java), "Missing external Java " + major + " executable: " + java);
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-version").redirectErrorStream(true);
        Process process = start(builder);
        try {
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            assertTrue(finished, "External Java version check timed out: " + java);
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.exitValue() == 0, "External Java could not start: " + output);
            String expected = major == 8 ? "1.8" : "\"" + major + ".";
            assertTrue(output.contains(expected), "Expected Java " + major + " but got: " + output);
            return java;
        } finally {
            terminateTree(process);
        }
    }

    /**
     * 显式固定子进程文本编码，不假定 JDK 18 及以上版本会用 file.encoding 设置输出流编码。
     */
    static List<String> jarCommand(Path java, int javaMajor, Path jar, Path passwordFile, List<String> applicationArgs) {
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Dfile.encoding=UTF-8");
        if (javaMajor >= 18) {
            command.add("-Dstdout.encoding=UTF-8");
            command.add("-Dstderr.encoding=UTF-8");
        }
        if (passwordFile != null) {
            command.add("-Dxjar.plus.password-file=" + passwordFile.toAbsolutePath());
        }
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.addAll(applicationArgs);
        return command;
    }

    /**
     * 仅在创建原生进程时临时启用无歧义的 Windows 参数转义，随后恢复原有系统属性。
     */
    static Process start(ProcessBuilder builder) throws IOException {
        if (!isWindows()) {
            return builder.start();
        }
        synchronized (WINDOWS_PROCESS_START_LOCK) {
            String previous = System.getProperty(WINDOWS_ARG_PROPERTY);
            try {
                System.setProperty(WINDOWS_ARG_PROPERTY, "false");
                return builder.start();
            } finally {
                if (previous == null) {
                    System.clearProperty(WINDOWS_ARG_PROPERTY);
                } else {
                    System.setProperty(WINDOWS_ARG_PROPERTY, previous);
                }
            }
        }
    }

    /**
     * 只回收本测试工具启动的 JVM 及其后代，避免失败时残留兼容矩阵服务进程。
     */
    static void terminateTree(Process process) {
        boolean interrupted = Thread.interrupted();
        try {
            List<ProcessHandle> descendants = process.toHandle().descendants().toList();
            for (int index = descendants.size() - 1; index >= 0; index--) {
                descendants.get(index).destroy();
            }
            process.destroy();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException timeoutCancellation) {
                    interrupted = true;
                }
            }
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            boolean parentStopped;
            try {
                parentStopped = process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException timeoutCancellation) {
                interrupted = true;
                parentStopped = !process.isAlive();
            }
            long descendantDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < descendantDeadline && descendants.stream().anyMatch(ProcessHandle::isAlive)) {
                try {
                    Thread.sleep(25);
                } catch (InterruptedException timeoutCancellation) {
                    interrupted = true;
                }
            }
            assertTrue(parentStopped && descendants.stream().noneMatch(ProcessHandle::isAlive),
                    "Could not reap child JVM process tree");
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 区分 Windows 与 Unix 的可执行文件命名，使同一测试源码可用于不同持续集成环境。
     */
    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
