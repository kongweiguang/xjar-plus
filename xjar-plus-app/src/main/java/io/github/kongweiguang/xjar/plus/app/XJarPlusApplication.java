/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app;

import io.github.kongweiguang.xjar.plus.app.cli.CliCancellationSession;
import io.github.kongweiguang.xjar.plus.app.cli.XJarPlusCommand;
import picocli.CommandLine;

import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 应用程序入口；保留可调用的执行方法，使测试无需退出 JVM 即可验证 CLI 行为。
 */
public final class XJarPlusApplication {
    /**
     * 禁止创建实例，因为命令运行器是静态进程边界。
     */
    private XJarPlusApplication() {
    }

    /**
     * 分发命令前将进程输出设为 UTF-8，避免 JVM 控制台默认编码破坏路径或中文消息。
     */
    public static void main(String[] args) {
        configureUtf8Streams();
        final int exitCode = execute(args, true);
        if (exitCode != 0 && !CliCancellationSession.shutdownStarted()) {
            System.exit(exitCode);
        }
    }

    /**
     * 暴露命令运行器，使集成测试和嵌入方无需接管进程即可捕获结果。
     */
    public static int execute(String... args) {
        return execute(args, false);
    }

    /**
     * 仅在可执行 CLI 路径使用显式 UTF-8 输出流，同时保留 execute() 对调用方流的所有权。
     */
    private static int execute(String[] args, boolean utf8Console) {
        final CommandLine commandLine = new CommandLine(new XJarPlusCommand());
        if (utf8Console) {
            commandLine.setOut(new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true));
            commandLine.setErr(new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true));
        }
        commandLine.setParameterExceptionHandler((exception, parseArgs) -> {
            final CommandLine failedCommand = exception.getCommandLine();
            failedCommand.getErr().println("参数错误；为避免泄露敏感输入，未回显原始参数值。请检查用法：");
            failedCommand.usage(failedCommand.getErr());
            return CommandLine.ExitCode.USAGE;
        });
        commandLine.setExecutionExceptionHandler((exception, command, parseResult) -> {
            command.getErr().println("操作失败：" + safeMessage(exception));
            return 1;
        });
        return commandLine.execute(args);
    }

    /**
     * 包装进程继承的标准流，使直接写入 System.out 或 System.err 的命令统一使用 UTF-8。
     */
    private static void configureUtf8Streams() {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
    }

    /**
     * 以易读方式报告预期的校验和文件错误，避免日常 CLI 输出被堆栈信息淹没。
     */
    private static String safeMessage(Exception exception) {
        final String message = exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        final String normalized = message.replaceAll("[\\r\\n\\t]+", " ").strip();
        return normalized.substring(0, Math.min(1_000, normalized.length()));
    }
}
