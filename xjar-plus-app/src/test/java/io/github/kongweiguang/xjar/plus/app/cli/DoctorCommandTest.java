/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoctorCommandTest {
    private static final Path FAKE_GO = Path.of("fake-go");

    /**
     * 即使缺少启动器打包前置条件，也保留独立加密能力。
     */
    @Test
    void missingGoDoesNotFailDoctor() throws Exception {
        final String output = run(null, executable -> {
            throw new AssertionError("Go process must not start when no executable was found");
        }, Duration.ofMillis(100), 128);

        assertTrue(output.contains("go.available=false"));
        assertTrue(output.contains("go.version=unknown"));
        assertTrue(output.contains("go.minimum=1.24"));
        assertTrue(output.contains("go.compatible=false"));
        assertTrue(output.contains("独立 JAR 加密无需 Go"));
    }

    /**
     * 固定与启动器打包相同的主版本门槛，并给出明确升级建议。
     */
    @Test
    void reportsGo123AsIncompatible() throws Exception {
        final String output = run(FAKE_GO, starter("go version go1.23.7 windows/amd64"),
                Duration.ofMillis(100), 128);

        assertTrue(output.contains("go.available=true"));
        assertTrue(output.contains("go.version=go1.23.7"));
        assertTrue(output.contains("go.compatible=false"));
        assertTrue(output.contains("升级 PATH 中的 Go"));
    }

    /**
     * 区分已安装但无法启动的程序和缺失的可执行文件，不因此让 doctor 失败。
     */
    @Test
    void reportsGoProcessStartFailure() throws Exception {
        final String output = run(FAKE_GO, executable -> {
            throw new IOException("private start detail");
        }, Duration.ofMillis(100), 128);

        assertTrue(output.contains("go.available=true"));
        assertTrue(output.contains("go.version=unknown"));
        assertTrue(output.contains("无法启动 Go"));
        assertFalse(output.contains("private start detail"));
    }

    /**
     * 即使输出看似受支持的版本，也拒绝非零退出码。
     */
    @Test
    void reportsNonzeroGoVersionExit() throws Exception {
        final String output = run(FAKE_GO, executable -> new FakeGoProcess(
                        "go version go1.24.0 windows/amd64".getBytes(StandardCharsets.UTF_8), true, 1),
                Duration.ofMillis(100), 128);

        assertTrue(output.contains("go.version=unknown"));
        assertTrue(output.contains("go.compatible=false"));
        assertTrue(output.contains("Go 版本命令执行失败"));
    }

    /**
     * 使用构建预检的 Go 版本语法，接受最低版本及之后的版本。
     */
    @Test
    void acceptsGo124AndLater() throws Exception {
        for (String version : new String[]{"go1.24.0", "go1.25.2"}) {
            final String output = run(FAKE_GO, starter("go version " + version + " windows/amd64"),
                    Duration.ofMillis(100), 128);
            assertTrue(output.contains("go.version=" + version));
            assertTrue(output.contains("go.compatible=true"));
        }
    }

    /**
     * 验证挂起的版本命令会在注入的时限内终止，并以诊断结果报告而非抛错。
     */
    @Test
    void timesOutGoVersionProbe() throws Exception {
        final long started = System.nanoTime();
        final String output = run(FAKE_GO, executable -> new FakeGoProcess(new byte[0], false, 0),
                Duration.ofMillis(80), 128);
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(output.contains("go.compatible=false"));
        assertTrue(output.contains("go version 检查超时"));
        assertTrue(elapsedMillis < 2_000, "The fake timeout should not consume the production timeout.");
    }

    /**
     * 拒绝未知版本文本，不将可执行文件的任意输出复制到终端诊断。
     */
    @Test
    void rejectsUnexpectedGoVersionOutput() throws Exception {
        final String output = run(FAKE_GO, starter("untrusted-command-output"), Duration.ofMillis(100), 128);

        assertTrue(output.contains("go.version=unknown"));
        assertTrue(output.contains("go.compatible=false"));
        assertTrue(output.contains("无法识别 Go 版本输出"));
        assertFalse(output.contains("untrusted-command-output"));
    }

    /**
     * 终止输出过大的子进程诊断，并仅保留配置的有界前缀。
     */
    @Test
    void boundsGoVersionOutput() throws Exception {
        final byte[] oversized = new byte[129];
        java.util.Arrays.fill(oversized, (byte) 'x');
        final String output = run(FAKE_GO, executable -> new FakeGoProcess(oversized, true, 0),
                Duration.ofMillis(100), 128);

        assertTrue(output.contains("go.compatible=false"));
        assertTrue(output.contains("Go 版本检查输出超过限制"));
        assertFalse(output.contains("x".repeat(129)));
    }

    /**
     * 创建进程工厂，为解析出的可执行文件返回固定输出。
     */
    private DoctorCommand.GoProcessStarter starter(String text) {
        final byte[] output = text.getBytes(StandardCharsets.UTF_8);
        return executable -> new FakeGoProcess(output, true, 0);
    }

    /**
     * 仅捕获当前命令的控制台输出，并在断言失败时也恢复全局流。
     */
    private String run(Path executable, DoctorCommand.GoProcessStarter starter,
                       Duration timeout, int maximumOutputBytes) throws Exception {
        final PrintStream originalOut = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            System.setOut(output);
            final DoctorCommand command = new DoctorCommand(() -> executable, starter, timeout, maximumOutputBytes);
            assertEquals(0, command.call());
            return captured.toString(StandardCharsets.UTF_8);
        } finally {
            System.setOut(originalOut);
            output.close();
        }
    }

    /**
     * 仅模拟 doctor 负责的进程操作，包括受控的非终止子进程。
     */
    private static final class FakeGoProcess extends Process {
        private final InputStream input;
        private final int exitCode;
        private boolean finished;

        /**
         * 允许测试提供有界输出，并确定性选择子进程正常完成或超时。
         */
        private FakeGoProcess(byte[] output, boolean finished, int exitCode) {
            this.input = new ByteArrayInputStream(output);
            this.finished = finished;
            this.exitCode = exitCode;
        }

        /**
         * 将测试夹具输出提供给与生产相同的异步有界读取器。
         */
        @Override
        public InputStream getInputStream() {
            return input;
        }

        /**
         * 保持 stderr 为空，因为生产逻辑会先将其合并到 stdout 再读取。
         */
        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        /**
         * 提供无交互输入的 stdin 流，因为 go version 不需要用户输入。
         */
        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        /**
         * 模拟挂起子进程时，等待时间不会超过调用方指定时限。
         */
        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (!finished) {
                unit.sleep(timeout);
            }
            return finished;
        }

        /**
         * doctor 请求清理时，使已超时的模拟子进程立即终止。
         */
        @Override
        public Process destroyForcibly() {
            finished = true;
            return this;
        }

        /**
         * 正常清理时也采用相同的确定性终止行为。
         */
        @Override
        public void destroy() {
            destroyForcibly();
        }

        /**
         * 向生产清理路径报告测试夹具生命周期。
         */
        @Override
        public boolean isAlive() {
            return !finished;
        }

        /**
         * 子进程完成后返回预设的退出码。
         */
        @Override
        public int exitValue() {
            if (!finished) {
                throw new IllegalThreadStateException("fake Go process is still running");
            }
            return exitCode;
        }

        /**
         * 实现阻塞等待，不额外启动测试进程。
         */
        @Override
        public int waitFor() throws InterruptedException {
            while (!finished) {
                TimeUnit.MILLISECONDS.sleep(1);
            }
            return exitCode;
        }
    }
}
