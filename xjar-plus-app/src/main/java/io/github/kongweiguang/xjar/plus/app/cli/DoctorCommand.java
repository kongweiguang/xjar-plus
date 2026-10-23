/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import picocli.CommandLine.Command;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检查本机前置条件，不构建产物，也不修改系统配置。
 */
@Command(name = "doctor", mixinStandardHelpOptions = true, description = "检查当前 Java、Go 和可写目录环境。")
public final class DoctorCommand implements Callable<Integer> {
    private static final int MINIMUM_GO_MAJOR = 24;
    private static final int DEFAULT_MAXIMUM_GO_OUTPUT_BYTES = 4096;
    private static final Duration DEFAULT_GO_TIMEOUT = Duration.ofSeconds(3);
    private static final String GO_OUTPUT_LIMIT_REASON =
            "Go 版本检查输出超过限制；确认 PATH 中的 go 是标准 Go 命令。";
    private static final Pattern GO_VERSION = Pattern.compile("go version go1\\.(\\d+)(?:[. \\r\\n]|$)");

    private final GoExecutableFinder executableFinder;
    private final GoProcessStarter processStarter;
    private final Duration goTimeout;
    private final int maximumGoOutputBytes;

    /**
     * 通过 PATH 查找 Go 并执行本地版本探测，严格限制运行时间和输出大小。
     */
    public DoctorCommand() {
        this(DoctorCommand::findGoExecutable, DoctorCommand::startGoVersionProcess,
                DEFAULT_GO_TIMEOUT, DEFAULT_MAXIMUM_GO_OUTPUT_BYTES);
    }

    /**
     * 注入进程边界，便于验证版本、超时和异常输出处理。
     */
    DoctorCommand(GoExecutableFinder executableFinder, GoProcessStarter processStarter,
                  Duration goTimeout, int maximumGoOutputBytes) {
        this.executableFinder = Objects.requireNonNull(executableFinder, "executableFinder");
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
        this.goTimeout = Objects.requireNonNull(goTimeout, "goTimeout");
        if (goTimeout.isNegative() || goTimeout.isZero() || maximumGoOutputBytes < 1) {
            throw new IllegalArgumentException("Go probe limits must be positive");
        }
        this.maximumGoOutputBytes = maximumGoOutputBytes;
    }

    /**
     * 解析选定的 Go 可执行文件；未安装 Go 时不启动任何子进程。
     */
    private static Path findGoExecutable() {
        final String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        final boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        final String executableName = windows ? "go.exe" : "go";
        for (String directory : path.split(Pattern.quote(System.getProperty("path.separator")))) {
            try {
                final Path candidate = Path.of(directory, executableName);
                if (Files.isExecutable(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            } catch (InvalidPathException ignored) {
                // 无效 PATH 条目不能定位可执行文件，继续查找即可。
            }
        }
        return null;
    }

    /**
     * 直接启动指定编译器，不经过 shell，也不触发自动工具链下载。
     */
    private static Process startGoVersionProcess(Path executable) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder(executable.toString(), "version").redirectErrorStream(true);
        builder.environment().put("GOTOOLCHAIN", "local");
        return builder.start();
    }

    /**
     * 使用 Go 编译器预检的版本语法和最低主版本策略，且不生成构建日志文件。
     */
    private static GoStatus evaluateVersion(String executablePath, String output) {
        final Matcher matcher = GO_VERSION.matcher(output);
        if (!matcher.find()) {
            return unavailable(executablePath,
                    "无法识别 Go 版本输出；确认 PATH 中的 Go 工具链可运行 go version。");
        }
        final int major;
        try {
            major = Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException failure) {
            return unavailable(executablePath, "Go 版本号超出支持范围；请安装正式版 Go 1.24+。");
        }
        final int tokenStart = matcher.start() + "go version ".length();
        int tokenEnd = tokenStart;
        while (tokenEnd < output.length() && !Character.isWhitespace(output.charAt(tokenEnd))) {
            tokenEnd++;
        }
        final String version = output.substring(tokenStart, tokenEnd);
        if (major < MINIMUM_GO_MAJOR) {
            return new GoStatus(true, executablePath, version, false,
                    "检测到 " + version + "，Go 启动器打包要求 Go 1.24+；升级 PATH 中的 Go 后重试。");
        }
        return new GoStatus(true, executablePath, version, true,
                version + " 满足 Go 启动器打包最低版本要求。");
    }

    /**
     * 明确区分不可用和不兼容状态，并提供可执行的后续建议。
     */
    private static GoStatus unavailable(String path, String reason) {
        return new GoStatus(true, path, "unknown", false, reason);
    }

    /**
     * 关闭进程流前先停止已超时或失败的探测进程。
     */
    private static void stopProcess(Process process) {
        if (process.isAlive()) {
            process.destroyForcibly();
            try {
                process.waitFor(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 进程流关闭后给有界输出读取线程短暂时间退出。
     */
    private static void joinQuietly(Thread reader) {
        try {
            reader.join(250);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 单独报告 Go 环境状态，确保未安装 Go 不影响独立加密 JAR 的使用。
     */
    @Override
    public Integer call() {
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("java.home=" + System.getProperty("java.home"));
        printGoStatus(inspectGo());
        final Path workingDirectory = Path.of("").toAbsolutePath();
        System.out.println("user.dir=" + workingDirectory);
        System.out.println("user.dir.writable=" + Files.isWritable(workingDirectory));
        return 0;
    }

    /**
     * 保持状态字段结构稳定，同时让 doctor 的其他检查在此项失败时仍可使用。
     */
    private void printGoStatus(GoStatus status) {
        System.out.println("go.available=" + status.available());
        System.out.println("go.path=" + status.path());
        System.out.println("go.version=" + status.version());
        System.out.println("go.minimum=1." + MINIMUM_GO_MAJOR);
        System.out.println("go.compatible=" + status.compatible());
        System.out.println("go.reason=" + status.reason());
    }

    /**
     * 只查询一次 Go 版本；即使安装不可用，也将其作为诊断结果而非命令失败。
     */
    private GoStatus inspectGo() {
        final Path executable = executableFinder.find();
        if (executable == null) {
            return new GoStatus(false, "not-found", "unknown", false,
                    "未找到 Go；独立 JAR 加密无需 Go，打包 Go 启动器请安装 Go 1.24+ 并加入 PATH。");
        }
        return probeGoVersion(executable.toAbsolutePath().normalize());
    }

    /**
     * 限制子进程时长和捕获字节数，防止异常 PATH 条目卡死或耗尽 doctor。
     */
    private GoStatus probeGoVersion(Path executable) {
        final String executablePath = executable.toString();
        final Process process;
        try {
            process = processStarter.start(executable);
        } catch (IOException | SecurityException failure) {
            return unavailable(executablePath, "无法启动 Go；检查 PATH 中的程序和执行权限后重试。");
        }
        Thread outputReader = null;
        try (InputStream childOutput = process.getInputStream();
             OutputStream childInput = process.getOutputStream()) {
            try {
                final BoundedOutput output = new BoundedOutput(childOutput, maximumGoOutputBytes);
                outputReader = new Thread(output, "xjar-plus-doctor-go-version");
                outputReader.setDaemon(true);
                outputReader.start();
                childInput.flush();
                final long deadline = System.nanoTime() + goTimeout.toNanos();
                while (true) {
                    if (output.exceeded()) {
                        return unavailable(executablePath, GO_OUTPUT_LIMIT_REASON);
                    }
                    final long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        return unavailable(executablePath, "go version 检查超时；检查 Go 安装或安全软件后重试。");
                    }
                    if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) {
                        break;
                    }
                }
                outputReader.join(TimeUnit.MILLISECONDS.toMillis(500));
                if (outputReader.isAlive()) {
                    return unavailable(executablePath, "Go 版本输出流未关闭；检查该 Go 安装后重试。");
                }
                if (output.exceeded()) {
                    return unavailable(executablePath, GO_OUTPUT_LIMIT_REASON);
                }
                if (output.failure() != null || process.exitValue() != 0) {
                    return unavailable(executablePath, "Go 版本命令执行失败；检查 PATH 中的 Go 安装后重试。");
                }
                return evaluateVersion(executablePath, output.text());
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return unavailable(executablePath, "Go 版本检查被中断；可重试 doctor，独立 JAR 加密不受影响。");
            } finally {
                stopProcess(process);
            }
        } catch (IOException failure) {
            return unavailable(executablePath, "读取 Go 版本输出失败；检查 Go 安装后重试。");
        } finally {
            if (outputReader != null) {
                joinQuietly(outputReader);
            }
        }
    }

    /**
     * 为测试提供可替换的可执行文件查找能力，不修改进程环境变量。
     */
    @FunctionalInterface
    interface GoExecutableFinder {
        /**
         * 返回解析出的可执行文件；未安装 Go 时返回 null。
         */
        Path find();
    }

    /**
     * 创建版本探测子进程，使测试可以确定性验证超时和输出上限。
     */
    @FunctionalInterface
    interface GoProcessStarter {
        /**
         * 为选定的可执行文件运行 go version，不接管后续进程清理。
         */
        Process start(Path executable) throws IOException;
    }

    /**
     * 从查找阶段到 CLI 输出只传递非秘密诊断字段。
     */
    private record GoStatus(boolean available, String path, String version, boolean compatible, String reason) {
    }

    /**
     * 只保留允许的输出前缀，并在噪声进程耗尽内存前标记超限。
     */
    private static final class BoundedOutput implements Runnable {
        private final InputStream input;
        private final byte[] bytes;
        private volatile int size;
        private volatile boolean exceeded;
        private volatile IOException failure;

        /**
         * 按捕获上限精确分配缓冲区，超出的输出不保留。
         */
        private BoundedOutput(InputStream input, int maximumBytes) {
            this.input = input;
            this.bytes = new byte[maximumBytes];
        }

        /**
         * 在探测流程持有子进程流期间并发读取标准输出和错误输出。
         */
        @Override
        public void run() {
            final byte[] chunk = new byte[512];
            try {
                int count;
                while ((count = input.read(chunk)) >= 0) {
                    final int copyLength = Math.min(count, bytes.length - size);
                    System.arraycopy(chunk, 0, bytes, size, copyLength);
                    size += copyLength;
                    if (copyLength < count) {
                        exceeded = true;
                        return;
                    }
                }
            } catch (IOException readFailure) {
                failure = readFailure;
            }
        }

        /**
         * 返回读取线程通过 volatile 发布的超限标记。
         */
        private boolean exceeded() {
            return exceeded;
        }

        /**
         * 读取线程结束后返回其错误状态。
         */
        private IOException failure() {
            return failure;
        }

        /**
         * 仅将有界字节按 UTF-8 解码，以匹配标准 Go 版本语法。
         */
        private String text() {
            return new String(bytes, 0, size, StandardCharsets.UTF_8);
        }
    }
}
