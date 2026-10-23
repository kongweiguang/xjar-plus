/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * 将发行方初始化和许可证续期组织为两个易发现的独立操作。
 */
@Command(name = "license", mixinStandardHelpOptions = true, description = "管理发行方凭据和应用许可证。", subcommands = {
        LicenseInitCommand.class, LicenseIssueCommand.class
})
public final class LicenseCommand implements Runnable {
    /**
     * 子命令不完整时显示分组帮助，而不是猜测用户意图。
     */
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
