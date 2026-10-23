/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure.distribution;

import io.github.kongweiguang.xjar.plus.domain.model.CancellationProbe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 使用 argv 参数启动编译器并提供有界取消，不拼接 shell 命令字符串。
 */
public final class GoCompiler {
    private static final Pattern VERSION = Pattern.compile("go version go1\\.(\\d+)(?:[. \\r\\n]|$)");

    /**
     * 将子进程所有权保留在内部，不暴露可重复使用且无人管理的进程对象。
     */
    private GoCompiler() {
    }

    /**
     * 提前检查本地编译器，确保在耗时的归档保护前报告工具缺失。
     */
    public static void check(final Path executable, final Path workspace, final CancellationProbe cancellation)
            throws IOException {
        final Path log = workspace.resolve("go-version.log");
        run(List.of(tool(executable), "version"), workspace, log, null, cancellation, Duration.ofSeconds(15));
        final var matcher = VERSION.matcher(readDiagnostic(log));
        if (!matcher.find() || Integer.parseInt(matcher.group(1)) < 24) {
            throw new IOException("Go 1.24 or later is required to build the launcher");
        }
    }

    /**
     * 只生成所选操作系统和架构的程序，并拒绝依赖隐式工作区或 shell 行为。
     */
    public static void compile(final Path executable, final Path source, final String target, final Path output,
                               final CancellationProbe cancellation) throws IOException {
        final List<String> command = new ArrayList<>();
        command.add(tool(executable));
        command.addAll(List.of("build", "-trimpath", "-buildvcs=false", "-ldflags=-s -w", "-o",
                output.toAbsolutePath().toString(), "./cmd/launcher"));
        run(command, source, source.getParent().resolve("go-build.log"), target, cancellation, Duration.ofMinutes(10));
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("Go reported success without producing a launcher binary");
        }
    }

    /**
     * 对显式编译器路径只解析一次；未指定时仍允许使用已安装 Go 的 PATH 查找。
     */
    private static String tool(final Path executable) {
        return executable == null ? "go" : executable.toAbsolutePath().normalize().toString();
    }

    /**
     * 快速轮询取消状态，并跟踪子孙进程以便清理，即使编译器提前退出也不例外。
     */
    private static void run(final List<String> command, final Path directory, final Path log, final String target,
                            final CancellationProbe cancellation, final Duration timeout) throws IOException {
        cancellation.check();
        final ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("GOWORK", "off");
        builder.environment().put("GOTOOLCHAIN", "local");
        builder.environment().put("GOFLAGS", "");
        configureTarget(builder, directory, target);
        final Process process = builder.start();
        final Set<ProcessHandle> owned = new LinkedHashSet<>();
        owned.add(process.toHandle());
        final long deadline = System.nanoTime() + timeout.toNanos();
        try {
            process.getOutputStream().close();
            while (true) {
                process.descendants().forEach(owned::add);
                cancellation.check();
                if (System.nanoTime() >= deadline) {
                    throw new IOException("Go compiler exceeded its time limit");
                }
                if (Files.exists(log) && Files.size(log) > 16L * 1024 * 1024) {
                    throw new IOException("Go compiler diagnostic output exceeded its size limit");
                }
                if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
            if (process.exitValue() != 0) {
                throw new IOException("Go compiler failed (exit " + process.exitValue() + "): " + readDiagnostic(log));
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Go compilation interrupted", failure);
        } finally {
            stop(owned);
        }
    }

    /**
     * 分离目标平台策略和进程监督，并避免内嵌密钥材料进入全局构建缓存。
     */
    private static void configureTarget(final ProcessBuilder builder, final Path directory, final String target)
            throws IOException {
        if (target == null) {
            return;
        }
        final String[] parts = target.split("-", -1);
        if (parts.length != 2 || !Set.of("windows", "linux", "darwin").contains(parts[0])
            || !Set.of("amd64", "arm64").contains(parts[1])) {
            throw new IOException("Unsupported Go target: " + target);
        }
        builder.environment().put("GOOS", parts[0]);
        builder.environment().put("GOARCH", parts[1]);
        builder.environment().put("CGO_ENABLED", "0");
        final Path cache = Files.createDirectory(directory.getParent().resolve("go-cache"));
        final Path temporary = Files.createDirectory(directory.getParent().resolve("go-temporary"));
        builder.environment().put("GOCACHE", cache.toString());
        builder.environment().put("GOTMPDIR", temporary.toString());
    }

    /**
     * 仅保留有界的编译诊断，不序列化命令中可能包含秘密的载荷文件。
     */
    private static String readDiagnostic(final Path log) throws IOException {
        try (var input = Files.newInputStream(log)) {
            return new String(input.readNBytes(8192), StandardCharsets.UTF_8).strip();
        }
    }

    /**
     * 先取消已发现的子孙进程，再等待被强制终止的进程释放私有文件句柄。
     */
    private static void stop(final Set<ProcessHandle> handles) throws IOException {
        final List<ProcessHandle> reverse = new ArrayList<>(handles).reversed();
        final boolean interrupted = Thread.interrupted();
        try {
            reverse.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
            waitUntilStopped(reverse, 5);
            reverse.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            waitUntilStopped(reverse, 2);
            if (reverse.stream().anyMatch(ProcessHandle::isAlive)) {
                throw new IOException("Compiler process cleanup did not finish; private staging may remain");
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 所有子孙进程共用同一截止时间，避免每个进程分别等待而将超时乘以进程数量。
     */
    private static void waitUntilStopped(final List<ProcessHandle> handles, final int seconds) throws IOException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (handles.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while reaping compiler processes", failure);
            }
        }
    }
}
