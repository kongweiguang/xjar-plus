/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.app.ui.WebUiCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * 命令树保持入口精简，并将归档与许可证操作统一交给核心处理。
 */
@Command(name = "xjar-plus", mixinStandardHelpOptions = true, version = "xjar-plus 2.0.0",
        description = "加密、检查和打包 Java 应用。", subcommands = {
        EncryptCommand.class, PackageCommand.class, InspectCommand.class, VerifyCommand.class,
        DecryptCommand.class, DoctorCommand.class, LicenseCommand.class, WebUiCommand.class
})
public final class XJarPlusCommand implements Runnable {
    /**
     * 根命令只显示帮助，不自动打开界面或等待输入凭据。
     */
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
