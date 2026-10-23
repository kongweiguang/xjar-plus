/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.CancellationToken;
import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.LauncherRequest;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 复用独立模式的加密请求来打包由 Go 监督运行的启动器。
 */
@Command(name = "package", mixinStandardHelpOptions = true, description = "打包含许可证监督的 Go 启动器。")
public final class PackageCommand implements Callable<Integer> {
    @Mixin
    private EncryptionOptions encryption;

    @Mixin
    private PasswordOptions password;

    @Option(names = "--app-id", required = true, description = "许可证绑定的稳定应用标识。")
    private String appId;

    @Option(names = "--issuer-file", required = true, description = "发行方凭据文件，内容不会输出到日志。")
    private Path issuerFile;

    @Option(names = "--target", required = true,
            description = "目标平台：windows-amd64/windows-arm64/linux-amd64/linux-arm64/darwin-amd64/darwin-arm64。")
    private String target;

    @Option(names = "--jdk-zip", description = "可选的目标 JDK 压缩包；省略时使用目标机 Java。")
    private Path jdkZip;

    @Option(names = "--go-executable", description = "可选 Go 编译器路径；省略时从 PATH 查找。")
    private Path goExecutable;

    @Option(names = "--jvm-arg", description = "重复传入 JVM 参数，顺序保持不变。")
    private List<String> jvmArgs;

    @Option(names = "--app-arg", description = "重复传入应用参数，顺序保持不变。")
    private List<String> appArgs;

    @Option(names = "--not-before", description = "许可证生效时间，ISO-8601 UTC instant。")
    private Instant notBefore;

    @Option(names = "--not-after", description = "许可证到期时间，ISO-8601 UTC instant；省略表示永久。")
    private Instant notAfter;

    /**
     * 打包后关闭请求持有的秘密副本，并始终清除调用方持有的密码字符数组。
     */
    @Override
    public Integer call() throws Exception {
        try (CliCancellationSession cancellation = new CliCancellationSession()) {
            final char[] secret = password.resolve();
            try {
                try (LauncherRequest launcherRequest = request(secret, cancellation)) {
                    CommandOutput.printBuild(XJarPlus.packageLauncher(launcherRequest));
                }
                return 0;
            } finally {
                Arrays.fill(secret, '\0');
            }
        }
    }

    /**
     * 为不执行实际构建的命令解析测试创建不可取消请求，以保持结果确定。
     */
    @SuppressWarnings({"PMD.CloseResource", "PMD.UseTryWithResources"})
    LauncherRequest request(char[] secret) {
        return request(secret, CancellationToken.NONE);
    }

    /**
     * 将命令级关闭取消源传递给共享归档处理和编译流程。
     */
    @SuppressWarnings({"PMD.CloseResource", "PMD.UseTryWithResources"})
    LauncherRequest request(char[] secret, CancellationToken cancellation) {
        final EncryptionRequest encryptionRequest =
                encryption.requestForLauncher(secret, new ConsoleProgress(), cancellation);
        boolean transferred = false;
        try {
            final LauncherRequest.Builder builder = LauncherRequest.builder()
                    .encryption(encryptionRequest)
                    .output(encryption.output())
                    .target(target)
                    .issuerFile(issuerFile)
                    .appId(appId)
                    .jdkZip(jdkZip)
                    .notAfter(notAfter);
            if (notBefore != null) {
                builder.notBefore(notBefore);
            }
            if (goExecutable != null) {
                builder.goExecutable(goExecutable);
            }
            if (jvmArgs != null) {
                jvmArgs.forEach(builder::jvmArg);
            }
            if (appArgs != null) {
                appArgs.forEach(builder::appArg);
            }
            final LauncherRequest launcherRequest = builder.build();
            transferred = true;
            return launcherRequest;
        } finally {
            if (!transferred) {
                encryptionRequest.close();
            }
        }
    }
}
