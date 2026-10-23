/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.ui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 负责原生文件选择子进程，使界面服务关闭时可以取消所有未结束的对话框辅助进程。
 */
public final class NativePathPicker implements AutoCloseable {
    private static final Duration PICKER_TIMEOUT = Duration.ofSeconds(300);
    private static final String PICKER_STAGE_PREFIX = "XJP_PICKER_STAGE=";
    private static final String PICKER_CONTEXT_PREFIX = "XJP_PICKER_CONTEXT ";
    private static final String PICKER_WINDOW_PREFIX = "XJP_PICKER_WINDOW ";
    private static final String PICKER_WINDOW_STATE_PREFIX = "XJP_PICKER_WINDOW_STATE ";
    private static final String PICKER_DEBUG_PROPERTY = "xjar.plus.debug-picker";
    private static final Pattern PICKER_CONTEXT = Pattern.compile(
            "^XJP_PICKER_CONTEXT session=([0-9]{1,5}) station=([A-Za-z0-9_.-]{1,64}) "
            + "desktop=([A-Za-z0-9_.-]{1,64}) apartment=(STA|MTA|Unknown) "
            + "startup-flags=([A-F0-9]{8}) startup-show=([0-9]{1,5}) "
            + "owner=(none|console) owner-visible=(true|false)$");
    private static final Pattern PICKER_WINDOW = Pattern.compile(
            "^XJP_PICKER_WINDOW visible-before=(true|false) visible-after=(true|false) activated=(true|false)$");
    private static final Pattern PICKER_WINDOW_STATE = Pattern.compile(
            "^XJP_PICKER_WINDOW_STATE title-length=([0-9]{1,10}) rect-positive=(true|false) "
            + "cloak-hr=([A-F0-9]{8}) cloak-flags=(unknown|[A-F0-9]{8})$");
    private static final Set<String> PICKER_STAGES = Set.of("helper-compile-enter", "helper-compile-return",
            "helper-call-enter", "helper-call-return", "com-create-enter", "com-created",
            "dialog-configured", "dialog-events-advised", "dialog-show-enter", "dialog-show-return",
            "dialog-event-folder-change", "dialog-window-handle-missing", "dialog-window-query-failed",
            "dialog-window-visible", "dialog-window-still-hidden", "dialog-unadvise-failed");
    private static final int MAX_PICKER_DIAGNOSTIC_LINE_BYTES = 256;
    private final Semaphore singlePicker = new Semaphore(1);
    private final PickerProcessCoordinator processes = new PickerProcessCoordinator();

    /**
     * 供测试验证秘密和任意子进程输出均不会进入应用日志。
     */
    static String safeDiagnosticMessage(String line) {
        final String normalized = line.strip();
        if (normalized.startsWith(PICKER_STAGE_PREFIX)) {
            final String stage = normalized.substring(PICKER_STAGE_PREFIX.length());
            return PICKER_STAGES.contains(stage) ? "xjar-plus: Windows picker stage=" + stage : null;
        }
        if (normalized.startsWith(PICKER_CONTEXT_PREFIX)) {
            final Matcher matcher = PICKER_CONTEXT.matcher(normalized);
            if (matcher.matches()) {
                return "xjar-plus: Windows picker session=" + matcher.group(1)
                       + " station=" + matcher.group(2) + " desktop=" + matcher.group(3)
                       + " apartment=" + matcher.group(4) + " startup-flags=" + matcher.group(5)
                       + " startup-show=" + matcher.group(6) + " owner=" + matcher.group(7)
                       + " visible=" + matcher.group(8);
            }
        }
        if (normalized.startsWith(PICKER_WINDOW_PREFIX)) {
            final Matcher matcher = PICKER_WINDOW.matcher(normalized);
            if (matcher.matches()) {
                return "xjar-plus: Windows picker dialog visible-before=" + matcher.group(1)
                       + " visible-after=" + matcher.group(2) + " activated=" + matcher.group(3);
            }
        }
        if (normalized.startsWith(PICKER_WINDOW_STATE_PREFIX)) {
            final Matcher matcher = PICKER_WINDOW_STATE.matcher(normalized);
            if (matcher.matches()) {
                return "xjar-plus: Windows picker dialog title-length=" + matcher.group(1)
                       + " rect-positive=" + matcher.group(2) + " cloak-hr=" + matcher.group(3)
                       + " cloak-flags=" + matcher.group(4);
            }
        }
        return null;
    }

    /**
     * 默认不向界面错误暴露 COM 内部标记，同时保留 shell 提供的可操作失败信息。
     */
    static String stripPickerDiagnosticLines(String message) {
        final StringBuilder result = new StringBuilder();
        for (String line : message.split("\\R")) {
            if (isPickerDiagnosticLine(line)) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(line.strip());
        }
        return result.toString();
    }

    /**
     * 只识别受控辅助进程标记，不信任或记录子进程提供的任意后缀。
     */
    private static boolean isPickerDiagnosticLine(String line) {
        return line.startsWith(PICKER_STAGE_PREFIX) || line.startsWith(PICKER_CONTEXT_PREFIX)
               || line.startsWith(PICKER_WINDOW_PREFIX) || line.startsWith(PICKER_WINDOW_STATE_PREFIX);
    }

    /**
     * 原生选择器详细诊断必须显式开启，避免普通界面使用产生实现细节日志。
     */
    static boolean debugPickerEnabled() {
        return Boolean.getBoolean(PICKER_DEBUG_PROPERTY);
    }

    /**
     * 串行打开原生对话框，避免多个系统选择器争抢焦点或混淆路径结果。
     */
    public String choose(String kind) throws IOException, InterruptedException {
        if (!"file".equals(kind) && !"save".equals(kind) && !"directory".equals(kind)) {
            throw new IllegalArgumentException("选择类型仅支持 file、save 或 directory。");
        }
        if (!singlePicker.tryAcquire()) {
            throw new IOException("已有系统选择窗口打开，请先完成或取消它。");
        }
        try {
            processes.begin();
            if (isWindows()) {
                return chooseWindows(kind);
            }
            if (isMac()) {
                return chooseMac(kind);
            }
            return chooseLinux(kind);
        } finally {
            processes.end();
            singlePicker.release();
        }
    }

    /**
     * 只取消当前选择器，使未显示或失焦的原生对话框能够立即恢复。
     */
    public boolean cancelCurrent() {
        return processes.cancel();
    }

    /**
     * 使用 STA COM 文件对话框辅助程序；工具包封装曾在未创建可见窗口时阻塞。
     */
    private String chooseWindows(String kind) throws IOException, InterruptedException {
        try (PrivatePickerWorkspace workspace = PrivatePickerWorkspace.create()) {
            final List<String> scriptArgs = powerShellArguments(kind, workspace.script());
            try {
                return runWindowsPicker("pwsh.exe", scriptArgs);
            } catch (PickerAbortException abort) {
                throw abort;
            } catch (IOException modernUnavailable) {
                System.err.println("未能启动 PowerShell 7，尝试 Windows PowerShell 5.1："
                                   + shortMessage(modernUnavailable.getMessage()));
            }
            try {
                return runWindowsPicker("powershell.exe", scriptArgs);
            } catch (PickerAbortException abort) {
                throw abort;
            } catch (IOException legacyFailure) {
                throw new IOException("无法启动系统文件选择器，请在网页中手动粘贴路径。" +
                                      " PowerShell 7 与 5.1 均不可用或失败："
                                      + shortMessage(legacyFailure.getMessage()), legacyFailure);
            }
        }
    }

    /**
     * 使用简短参数数组和私有脚本路径，避免超过 Windows CreateProcess 命令长度限制。
     */
    List<String> powerShellArguments(String kind, Path script) {
        if (!"file".equals(kind) && !"save".equals(kind) && !"directory".equals(kind)) {
            throw new IllegalArgumentException("选择类型仅支持 file、save 或 directory。");
        }
        return List.of("-NoLogo", "-NoProfile", "-NonInteractive", "-STA", "-File",
                script.toAbsolutePath().toString(), kind);
    }

    /**
     * 统一 shell 退出码，并将超时和显式取消保留为中止异常。
     */
    private String runWindowsPicker(String executable, List<String> arguments)
            throws IOException, InterruptedException {
        final ProcessResult result = processes.run(withExecutable(executable, arguments));
        if (result.exitCode == 0) {
            return result.stdout.isBlank() ? null : stripOneLineEnding(result.stdout);
        }
        if (result.exitCode == 2) {
            return null;
        }
        throw new IOException("Windows 原生选择器失败："
                              + shortMessage(stripPickerDiagnosticLines(result.stderr)));
    }

    /**
     * 使用 macOS 内置 AppleScript 文件面板，不安装或启动其他 GUI 工具包。
     */
    private String chooseMac(String kind) throws IOException, InterruptedException {
        final String script = switch (kind) {
            case "file" -> "POSIX path of (choose file with prompt \"选择文件\")";
            case "save" -> "POSIX path of (choose file name with prompt \"选择新输出文件\")";
            default -> "POSIX path of (choose folder with prompt \"选择目录\")";
        };
        final ProcessResult result = processes.run(List.of("osascript", "-e", script));
        if (result.exitCode != 0) {
            if (result.stderr.contains("User canceled") || result.stderr.contains("-128")) {
                return null;
            }
            throw new IOException("macOS 系统选择器失败：" + shortMessage(result.stderr));
        }
        return stripOneLineEnding(result.stdout);
    }

    /**
     * 使用已安装的桌面选择工具；如果系统没有可用工具，仍允许手工输入路径。
     */
    private String chooseLinux(String kind) throws IOException, InterruptedException {
        final String helper = commandOnPath("zenity") ? "zenity" : commandOnPath("kdialog") ? "kdialog" : null;
        if (helper == null) {
            throw new IOException("未找到 zenity 或 kdialog，请在网页中手动粘贴路径。");
        }
        final List<String> command = new ArrayList<>();
        command.add(helper);
        if ("zenity".equals(helper)) {
            command.add("--file-selection");
            command.add("--title=选择路径");
            if ("directory".equals(kind)) {
                command.add("--directory");
            } else if ("save".equals(kind)) {
                command.add("--save");
            }
        } else {
            command.add("directory".equals(kind) ? "--getexistingdirectory"
                    : "save".equals(kind) ? "--getsavefilename" : "--getopenfilename");
        }
        final ProcessResult result = processes.run(command);
        if (result.exitCode != 0) {
            if (result.stderr.isBlank()) {
                return null;
            }
            throw new IOException("Linux 系统选择器失败：" + shortMessage(result.stderr));
        }
        return stripOneLineEnding(result.stdout);
    }

    /**
     * 构造平台参数数组，不通过 shell 插值处理用户提供的路径。
     */
    private List<String> withExecutable(String executable, List<String> args) {
        final List<String> command = new ArrayList<>(args.size() + 1);
        command.add(executable);
        command.addAll(args);
        return command;
    }

    /**
     * 根据平台可执行文件后缀从 PATH 查找辅助程序。
     */
    private boolean commandOnPath(String name) {
        final String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String directory : path.split(java.util.regex.Pattern.quote(System.getProperty("path.separator")))) {
            if (Files.isExecutable(Path.of(directory, name))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单独判断操作系统，避免依赖可能被自定义的 Java 区域设置。
     */
    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    /**
     * 单独识别 macOS，使 Linux 选择工具失败时仍能提供准确建议。
     */
    private boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    /**
     * 移除一处进程行结束符，同时原样保留路径中的空格及其他字符。
     */
    private String stripOneLineEnding(String value) {
        if (value.endsWith("\r\n")) {
            return value.substring(0, value.length() - 2);
        }
        if (value.endsWith("\n") || value.endsWith("\r")) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }

    /**
     * 限制诊断文本长度，避免辅助进程的 stderr 撑满界面响应。
     */
    private String shortMessage(String message) {
        if (message == null || message.isBlank()) {
            return "无详细错误信息";
        }
        final String normalized = message.strip().replaceAll("[\\r\\n\\t]+", " ");
        return normalized.substring(0, Math.min(240, normalized.length()));
    }

    /**
     * 服务关闭时终止辅助进程，使原生对话框随服务一同关闭。
     */
    @Override
    public void close() {
        processes.close();
    }

    /**
     * 独立持有进程状态，确保对话框取消不会错过正在启动或关闭的辅助进程。
     */
    private static final class PickerProcessCoordinator implements AutoCloseable {
        private final Set<Process> children = ConcurrentHashMap.newKeySet();
        private final Object stateLock = new Object();
        private boolean active;
        private boolean cancellationRequested;
        private boolean closed;

        /**
         * 平台适配器启动辅助进程前，先开放一次取消窗口。
         */
        private void begin() throws IOException {
            synchronized (stateLock) {
                if (closed) {
                    throw new IOException("本地界面服务已关闭。");
                }
                active = true;
                cancellationRequested = false;
            }
        }

        /**
         * 关闭当前选择器的取消窗口，使后续请求从干净状态开始。
         */
        private void end() {
            synchronized (stateLock) {
                active = false;
                cancellationRequested = false;
            }
        }

        /**
         * 结束子进程前先标记活动请求，避免启动竞态重新弹出对话框。
         */
        private boolean cancel() {
            synchronized (stateLock) {
                if (!active) {
                    return false;
                }
                cancellationRequested = true;
            }
            children.forEach(Process::destroyForcibly);
            return true;
        }

        /**
         * 在执行超时限制的同时排空两个管道，避免辅助进程阻塞界面线程。
         */
        private ProcessResult run(List<String> command) throws IOException, InterruptedException {
            final Process process = startProcess(command);
            final CompletableFuture<byte[]> stdout =
                    CompletableFuture.supplyAsync(() -> readBytes(process.getInputStream(), false));
            final CompletableFuture<byte[]> stderr =
                    CompletableFuture.supplyAsync(() -> readBytes(process.getErrorStream(), true));
            try {
                awaitProcess(process);
                final byte[] out = stdout.join();
                final byte[] err = stderr.join();
                return new ProcessResult(process.exitValue(), new String(out, StandardCharsets.UTF_8),
                        new String(err, StandardCharsets.UTF_8));
            } finally {
                finishProcess(process);
            }
        }

        /**
         * 在状态锁保护下启动并登记辅助进程，避免取消请求错过进程交接。
         */
        private Process startProcess(List<String> command) throws IOException {
            synchronized (stateLock) {
                if (closed) {
                    throw new IOException("本地界面服务已关闭。");
                }
                if (cancellationRequested) {
                    throw new PickerCancelledException();
                }
                final Process process = new ProcessBuilder(command).start();
                children.add(process);
                return process;
            }
        }

        /**
         * 最多等待五分钟，并优先处理显式取消而不是辅助进程退出码。
         */
        private void awaitProcess(Process process) throws IOException, InterruptedException {
            try {
                if (!process.waitFor(PICKER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new PickerTimeoutException();
                }
                synchronized (stateLock) {
                    if (cancellationRequested) {
                        throw new PickerCancelledException();
                    }
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw e;
            }
        }

        /**
         * 从跟踪列表移除已退出进程，确保请求处理结束后没有辅助进程残留。
         */
        private void finishProcess(Process process) {
            children.remove(process);
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }

        /**
         * 异步读取子进程输出，只记录允许的非敏感选择器阶段标记。
         */
        private byte[] readBytes(java.io.InputStream input, boolean reportPickerStages) {
            try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                final byte[] buffer = new byte[4096];
                final ByteArrayOutputStream line = new ByteArrayOutputStream();
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    if (reportPickerStages) {
                        collectStageLines(buffer, count, line);
                    }
                }
                if (reportPickerStages && line.size() > 0) {
                    reportPickerStage(line.toString(StandardCharsets.US_ASCII));
                }
                return output.toByteArray();
            } catch (IOException e) {
                return new byte[0];
            }
        }

        /**
         * 解析有界 ASCII 阶段行，不将子进程的任意诊断写入应用日志。
         */
        private void collectStageLines(byte[] buffer, int count, ByteArrayOutputStream line) {
            for (int index = 0; index < count; index++) {
                final byte value = buffer[index];
                if (value == '\n') {
                    reportPickerStage(line.toString(StandardCharsets.US_ASCII));
                    line.reset();
                } else if (line.size() < MAX_PICKER_DIAGNOSTIC_LINE_BYTES) {
                    line.write(value);
                }
            }
        }

        /**
         * 只记录固定阶段和上下文模式，避免泄露路径或任意 PowerShell 输出。
         */
        private void reportPickerStage(String line) {
            if (!debugPickerEnabled()) {
                return;
            }
            final String message = safeDiagnosticMessage(line);
            if (message != null) {
                System.err.println(message);
            }
        }

        /**
         * HTTP 服务关闭时终止所有正在跟踪的辅助进程。
         */
        @Override
        public void close() {
            synchronized (stateLock) {
                closed = true;
                cancellationRequested = true;
            }
            children.forEach(Process::destroyForcibly);
            children.clear();
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }

    private abstract static class PickerAbortException extends IOException {
        /**
         * 对不同 shell 版本统一处理显式取消和超时，不自动回退再开窗口。
         */
        private PickerAbortException(String message) {
            super(message);
        }
    }

    private static final class PickerTimeoutException extends PickerAbortException {
        /**
         * 原选择器超时后禁止回退流程再次打开第二个对话框。
         */
        private PickerTimeoutException() {
            super("系统选择窗口超过 300 秒未关闭，已取消。");
        }
    }

    static final class PickerCancelledException extends PickerAbortException {
        /**
         * 区分用户主动取消和应向用户展示的辅助进程故障。
         */
        private PickerCancelledException() {
            super("系统选择窗口已取消。");
        }
    }
}
