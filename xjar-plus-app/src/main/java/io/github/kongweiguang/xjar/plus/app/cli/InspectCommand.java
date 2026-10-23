/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * 检查归档元数据，无需输入或暴露密码。
 */
@Command(name = "inspect", mixinStandardHelpOptions = true, description = "识别 JAR 类型及启动元数据。")
public final class InspectCommand implements Callable<Integer> {
    @Option(names = "--input", required = true, description = "JAR 文件路径。")
    private Path input;

    /**
     * 由核心识别归档类型，确保检查结果与打包行为采用相同判断规则。
     */
    @Override
    public Integer call() throws Exception {
        CommandOutput.printArchive(XJarPlus.inspect(input));
        return 0;
    }
}
