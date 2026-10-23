/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XJarPlusApplicationTest {
    /**
     * 验证空参数调用会展示帮助，且不会启动浏览器或本地服务。
     */
    @Test
    void emptyInvocationPrintsHelp() {
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream replacement = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(replacement);
            assertEquals(0, XJarPlusApplication.execute());
        } finally {
            System.setOut(original);
        }
        final String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Usage:"));
        assertTrue(output.contains("encrypt"));
        assertTrue(output.contains("ui"));
    }

    /**
     * 验证每个 CLI 操作在解析必填参数前都可使用自身帮助选项。
     */
    @Test
    void commandHelpDoesNotRequireInputs() {
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream replacement = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(replacement);
            assertEquals(0, XJarPlusApplication.execute("package", "--help"));
        } finally {
            System.setOut(original);
        }
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("--issuer-file"));
    }

    /**
     * 确保 picocli 解析诊断不会回显未知选项中可能包含的秘密值。
     */
    @Test
    void invalidOptionDoesNotEchoSuppliedSecret() {
        final PrintStream original = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final String secret = "cli-secret-that-must-not-appear";
        final int exitCode;
        try (PrintStream replacement = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setErr(replacement);
            exitCode = XJarPlusApplication.execute("encrypt", "--password=" + secret);
        } finally {
            System.setErr(original);
        }
        final String output = captured.toString(StandardCharsets.UTF_8);

        assertEquals(2, exitCode);
        assertTrue(output.contains("参数错误"));
        assertFalse(output.contains(secret));
    }

    /**
     * 确保可嵌入运行器继续使用调用方提供的流，不套用可执行程序的控制台策略。
     */
    @Test
    void executePreservesCallerOwnedOutputStream() {
        final PrintStream original = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (PrintStream replacement = new PrintStream(captured, true, StandardCharsets.UTF_8)) {
            System.setOut(replacement);
            assertEquals(0, XJarPlusApplication.execute("doctor"));
            assertSame(replacement, System.out);
        } finally {
            System.setOut(original);
        }
        assertTrue(captured.toString(StandardCharsets.UTF_8).contains("user.dir="));
    }

    /**
     * 使用旧式控制台默认值调用真实进程入口，覆盖所有 CLI 输出路径。
     */
    @Test
    void mainWritesUtf8WhenJvmConsoleDefaultsToGbk(@TempDir Path temporaryDirectory) throws Exception {
        final Path workingDirectory = Files.createDirectory(temporaryDirectory.resolve("包含中文的工作目录"));

        final ChildOutput help = runChild(workingDirectory, temporaryDirectory, "--help");
        assertEquals(0, help.exitCode());
        assertContainsUtf8(help.output(), "加密");

        final ChildOutput doctor = runChild(workingDirectory, temporaryDirectory, "doctor");
        assertEquals(0, doctor.exitCode());
        assertContainsUtf8(doctor.output(), "user.dir=" + workingDirectory.toAbsolutePath());

        final ChildOutput invalid = runChild(workingDirectory, temporaryDirectory, "encrypt", "--unknown-option");
        assertEquals(2, invalid.exitCode());
        assertContainsUtf8(invalid.output(), "参数错误");
    }

    /**
     * 使用 GBK 流默认值启动新 JVM，使测试检查实际字节而不是父进程解码后的显示。
     */
    private ChildOutput runChild(Path workingDirectory, Path temporaryDirectory, String... arguments)
            throws IOException, InterruptedException {
        final boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        final Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        final Path outputFile = Files.createTempFile(temporaryDirectory, "cli-output-", ".bin");
        final List<String> command = new ArrayList<>();
        command.add(javaExecutable.toString());
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dstdout.encoding=GBK");
        command.add("-Dstderr.encoding=GBK");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(XJarPlusApplication.class.getName());
        command.addAll(List.of(arguments));

        final Process process = new ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("CLI 子进程超过 30 秒未退出。");
            }
            return new ChildOutput(process.exitValue(), Files.readAllBytes(outputFile));
        } finally {
            process.getInputStream().close();
            process.getErrorStream().close();
        }
    }

    /**
     * 将捕获的进程字节按 UTF-8 解码，并拒绝出现有损替换字符的结果。
     */
    private void assertContainsUtf8(byte[] output, String expected) {
        final String decoded = new String(output, StandardCharsets.UTF_8);
        assertTrue(decoded.contains(expected), "UTF-8 输出缺少预期文本：" + expected + "；实际输出：" + decoded);
        assertFalse(decoded.contains("\uFFFD"), "UTF-8 输出包含替换字符：" + decoded);
    }

    private record ChildOutput(int exitCode, byte[] output) {
    }
}
