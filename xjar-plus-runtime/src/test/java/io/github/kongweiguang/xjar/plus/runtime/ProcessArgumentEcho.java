/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 子 JVM fixture，以不依赖 Windows 控制台代码页的编码输出收到的参数。
 *
 * @author kongweiguang
 */
public final class ProcessArgumentEcho {
    static final String ARGUMENT_PREFIX = "XJP_ARG:";

    /**
     * 避免实例化这个仅用于提供静态进程入口的 fixture。
     */
    private ProcessArgumentEcho() {
    }

    /**
     * 分别输出每个参数，使父进程测试能够发现原生命令行解析导致的参数合并。
     */
    public static void main(String[] arguments) {
        for (String argument : arguments) {
            writeArgument(argument);
        }
        System.out.flush();
    }

    /**
     * 直接写 ASCII 协议字节，绕过子 JVM 的 stdout charset，避免测试把编码差异误判成 argv 损坏。
     */
    private static void writeArgument(String argument) {
        final String encoded = ARGUMENT_PREFIX + Base64.getEncoder().encodeToString(
                argument.getBytes(StandardCharsets.UTF_8)) + "\n";
        final byte[] bytes = encoded.getBytes(StandardCharsets.US_ASCII);
        System.out.write(bytes, 0, bytes.length);
    }
}
