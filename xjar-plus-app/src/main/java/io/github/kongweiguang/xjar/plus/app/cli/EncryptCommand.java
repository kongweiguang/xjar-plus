/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.Arrays;
import java.util.concurrent.Callable;

/**
 * 通过核心用例加密独立 JAR，不启动授权监督流程。
 */
@Command(name = "encrypt", mixinStandardHelpOptions = true, description = "加密生成可独立运行的 JAR。")
public final class EncryptCommand implements Callable<Integer> {
    @Mixin
    private EncryptionOptions encryption;

    @Mixin
    private PasswordOptions password;

    /**
     * 调用核心操作前解析凭据，并在结束后清除调用方持有的密码字符数组。
     */
    @Override
    public Integer call() throws Exception {
        try (CliCancellationSession cancellation = new CliCancellationSession()) {
            final char[] secret = password.resolve();
            try {
                try (EncryptionRequest request =
                             encryption.request(secret, new ConsoleProgress(), cancellation)) {
                    CommandOutput.printBuild(XJarPlus.encrypt(request));
                }
                return 0;
            } finally {
                Arrays.fill(secret, '\0');
            }
        }
    }
}
