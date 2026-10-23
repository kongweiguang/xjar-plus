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
            System.out.println(ARGUMENT_PREFIX + Base64.getEncoder().encodeToString(
                    argument.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
