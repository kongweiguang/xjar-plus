/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * 通过核心用例创建一次性、绑定应用的发行方凭据。
 */
@Command(name = "init", mixinStandardHelpOptions = true, description = "为一个 appId 创建发行方凭据。")
public final class LicenseInitCommand implements Callable<Integer> {
    @Option(names = "--app-id", required = true, description = "稳定应用标识，后续许可证续期沿用。")
    private String appId;

    @Option(names = "--output", required = true, description = "新发行方凭据文件路径。")
    private Path output;

    /**
     * 创建凭据时不输出密钥材料，也不允许意外覆盖已有凭据。
     */
    @Override
    public Integer call() throws Exception {
        CommandOutput.printBuild(XJarPlus.createIssuer(output, appId));
        return 0;
    }
}
