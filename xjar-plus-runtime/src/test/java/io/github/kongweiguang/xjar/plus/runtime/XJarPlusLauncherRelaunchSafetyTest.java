/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保证自动重启诊断信息安全，并拒绝无法透明重复执行的 JVM 副作用。
 *
 * @author kongweiguang
 */
class XJarPlusLauncherRelaunchSafetyTest {
    private static final String WINDOWS_ARGV_PROPERTY = "jdk.lang.Process.allowAmbiguousCommands";

    /**
     * 使用当前 JDK 可执行文件，因为测试目标正是该 JDK 的 Windows 命令行解析器。
     */
    private static List<String> echoCommand(List<String> arguments) {
        final List<String> command = new ArrayList<String>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString());
        command.add("-Dstdout.encoding=windows-1252");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ProcessArgumentEcho.class.getName());
        command.addAll(arguments);
        return command;
    }

    /**
     * 将引号转义与字符集限制分开验证，避免英文 Windows 在 Java 入口之前替换中文而掩盖真实契约。
     */
    private static List<String> argumentVector() {
        return Arrays.asList("", "two words", "quoted\"value", "C:\\dir with spaces\\tail\\",
                "slash\\\"quote");
    }

    /**
     * 真实启动当前 Java 并逐项比较字节；超时和断言失败均回收进程，避免测试遗留业务 JVM。
     */
    private static void assertArgumentRoundTrip(List<String> arguments) throws Exception {
        final Process child = XJarPlusLauncher.startChildProcess(new ProcessBuilder(echoCommand(arguments)));
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Argument probe did not finish within its deadline");
            assertEquals(0, child.exitValue());
            final List<String> expected = diagnosticArguments(arguments);
            final List<String> actual = readArgumentEncodings(child);
            assertEquals(expected, actual, () -> "UTF-8/Base64 argv expected=" + expected + " actual=" + actual);
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Argument probe could not be reaped");
        }
    }

    /**
     * 将 argv 编码为可读的 ASCII 诊断，避免 CI 日志把不同 Unicode 值都显示成问号。
     */
    private static List<String> diagnosticArguments(List<String> arguments) {
        final List<String> encoded = new ArrayList<String>();
        for (String argument : arguments) {
            encoded.add(Base64.getEncoder().encodeToString(argument.getBytes(StandardCharsets.UTF_8)));
        }
        return encoded;
    }

    /**
     * 只读取子进程的 Base64 记录，避免平台控制台编码掩盖 argv 损坏。
     */
    private static List<String> readArgumentEncodings(Process child) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final byte[] buffer = new byte[1024];
        try (InputStream input = child.getInputStream()) {
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    bytes.write(buffer, 0, count);
                }
            }
        }
        final List<String> arguments = new ArrayList<String>();
        for (String line : new String(bytes.toByteArray(), StandardCharsets.US_ASCII).split("\\R")) {
            if (line.startsWith(ProcessArgumentEcho.ARGUMENT_PREFIX)) {
                arguments.add(line.substring(ProcessArgumentEcho.ARGUMENT_PREFIX.length()));
            }
        }
        return arguments;
    }

    /**
     * 恢复测试对系统属性的修改，避免影响其他 runtime 测试，包括原属性不存在的情况。
     */
    private static void restoreProperty(String value) {
        if (value == null) {
            System.clearProperty(WINDOWS_ARGV_PROPERTY);
        } else {
            System.setProperty(WINDOWS_ARGV_PROPERTY, value);
        }
    }

    /**
     * 仅在 Windows 上验证进程创建行为，避免在 Unix 上对无效果的逻辑作出断言。
     */
    private static void assumeWindows() {
        Assumptions.assumeTrue(File.separatorChar == '\\', "Windows ProcessImpl argv quoting is platform-specific");
    }

    /**
     * 只报告 agent 类别，因为启动路径和 agent 参数可能包含部署秘密。
     */
    @Test
    void doesNotEchoAgentArgumentsThatMayContainSecrets() {
        final String category = XJarPlusLauncher.unsupportedRelaunchOption(
                "-javaagent:C:\\private\\agent.jar=token-value");

        assertEquals("Java agent", category);
        assertFalse(category.contains("private"));
        assertFalse(category.contains("token-value"));
    }

    /**
     * 在子 JVM 重复环境变量提供的插桩前，识别继承的工具通道 agent。
     */
    @Test
    void rejectsUnsafeEnvironmentOptionsWithoutEchoingTheirValues() {
        final String category = XJarPlusLauncher.unsupportedRelaunchEnvironment(Collections.singletonMap(
                "JAVA_TOOL_OPTIONS", "-javaagent:C:\\private\\agent.jar=token-value"));

        assertEquals("Java agent injected by JAVA_TOOL_OPTIONS", category);
        assertFalse(category.contains("private"));
        assertFalse(category.contains("token-value"));
    }

    /**
     * 避免两个 JVM 争用同一个远程管理端口，同时保留普通调优参数。
     */
    @Test
    void rejectsFixedJmxPortsAndAllowsSideEffectFreeOptions() {
        assertEquals("fixed-port JMX configuration", XJarPlusLauncher.unsupportedRelaunchOption(
                "-Dcom.sun.management.jmxremote.rmi.port=9010"));
        assertEquals("fixed-port JMX configuration", XJarPlusLauncher.unsupportedRelaunchOption(
                "-Dcom.sun.management.config.file=C:\\private\\management.properties"));
        assertNull(XJarPlusLauncher.unsupportedRelaunchOption("-Xmx512m"));
    }

    /**
     * 通过子 JVM 验证操作系统解析行为，避免测试自身再增加一层 shell 引号处理。
     */
    @Test
    void preservesWindowsArgumentVectorAndRestoresExplicitProperty() throws Exception {
        assumeWindows();
        final String original = System.getProperty(WINDOWS_ARGV_PROPERTY);
        try {
            System.setProperty(WINDOWS_ARGV_PROPERTY, "true");
            assertArgumentRoundTrip(argumentVector());
            assertEquals("true", System.getProperty(WINDOWS_ARGV_PROPERTY));
        } finally {
            restoreProperty(original);
        }
    }

    /**
     * 保留 JDK 属性原本不存在的状态，避免持久添加系统属性改变应用行为。
     */
    @Test
    void restoresAbsentPropertyAfterSuccessfulStart() throws Exception {
        assumeWindows();
        final String original = System.getProperty(WINDOWS_ARGV_PROPERTY);
        try {
            System.clearProperty(WINDOWS_ARGV_PROPERTY);
            assertArgumentRoundTrip(argumentVector());
            assertNull(System.getProperty(WINDOWS_ARGV_PROPERTY));
        } finally {
            restoreProperty(original);
        }
    }

    /**
     * 中文代码页必须真实传回中文，其他代码页必须明确拒绝；两种环境均有断言，不跳过 Unicode 行为。
     */
    @Test
    void handlesUnicodeAccordingToTheActualWindowsNativeCharset() throws Exception {
        assumeWindows();
        final List<String> arguments = Collections.singletonList("中文参数");
        final Charset nativeCharset = Charset.forName(System.getProperty("sun.jnu.encoding"));
        final String original = System.getProperty(WINDOWS_ARGV_PROPERTY);
        try {
            System.setProperty(WINDOWS_ARGV_PROPERTY, "true");
            if (nativeCharset.newEncoder().canEncode(arguments.get(0))) {
                assertArgumentRoundTrip(arguments);
            } else {
                final IOException failure = assertThrows(IOException.class,
                        () -> XJarPlusLauncher.startChildProcess(new ProcessBuilder(echoCommand(arguments))));
                assertTrue(failure.getMessage().contains("cannot preserve an argument"));
                assertFalse(failure.getMessage().contains(arguments.get(0)));
            }
            assertEquals("true", System.getProperty(WINDOWS_ARGV_PROPERTY));
        } finally {
            restoreProperty(original);
        }
    }

    /**
     * 在每个平台固定覆盖英文代码页拒绝、中文与 UTF-8 可用、非法代理项及未知编码，不依赖 CI 区域设置。
     */
    @Test
    void validatesNativeEncodingWithoutLeakingArguments() throws Exception {
        XJarPlusLauncher.validateWindowsCommandEncoding(argumentVector(), "windows-1252");
        XJarPlusLauncher.validateWindowsCommandEncoding(Collections.singletonList("中文参数"), "GBK");
        XJarPlusLauncher.validateWindowsCommandEncoding(Collections.singletonList("中文参数\uD83D\uDD10"), "UTF-8");
        final IOException unrepresentable = assertThrows(IOException.class,
                () -> XJarPlusLauncher.validateWindowsCommandEncoding(Collections.singletonList("private-token-中文"), "windows-1252"));
        assertTrue(unrepresentable.getMessage().contains("cannot preserve an argument"));
        assertFalse(unrepresentable.getMessage().contains("private-token"));
        assertThrows(IOException.class,
                () -> XJarPlusLauncher.validateWindowsCommandEncoding(Collections.singletonList("\uD800"), "UTF-8"));
        for (String encoding : Arrays.asList(null, "", "private-unknown-charset")) {
            final IOException unknown = assertThrows(IOException.class,
                    () -> XJarPlusLauncher.validateWindowsCommandEncoding(argumentVector(), encoding));
            assertTrue(unknown.getMessage().contains("cannot determine"));
            assertFalse(unknown.getMessage().contains("private-unknown"));
            assertNull(unknown.getCause());
        }
    }

    /**
     * 验证即使 ProcessBuilder 无法创建子进程，临时设置也会恢复。
     */
    @Test
    void restoresPropertyWhenProcessStartFails() {
        assumeWindows();
        final String original = System.getProperty(WINDOWS_ARGV_PROPERTY);
        try {
            System.setProperty(WINDOWS_ARGV_PROPERTY, "false");
            final String missingExecutable = Paths.get(System.getProperty("java.home"), "bin",
                    "xjar-plus-missing-" + System.nanoTime() + ".exe").toString();
            assertThrows(IOException.class, () -> XJarPlusLauncher.startChildProcess(
                    new ProcessBuilder(missingExecutable)));
            assertEquals("false", System.getProperty(WINDOWS_ARGV_PROPERTY));
        } finally {
            restoreProperty(original);
        }
    }
}
