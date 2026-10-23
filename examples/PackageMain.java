/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.examples;

import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.LauncherRequest;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import io.github.kongweiguang.xjar.plus.format.Passwords;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * 授权分发示例：发布者凭据只创建一次，并在后续构建中持续复用。
 */
public final class PackageMain {
    /**
     * 禁止实例化仅供执行的示例类，避免产生无意义的对象状态。
     */
    private PackageMain() {
    }

    /**
     * 要求使用已有发布者凭据，避免重复执行示例时静默变更应用的授权密钥。
     */
    public static void main(final String[] args) throws Exception {
        if (args.length < 6 || args.length > 7) {
            throw new IllegalArgumentException("Usage: PackageMain <input.jar> <output-directory> "
                                               + "<password-file> <issuer-file> <app-id> <target> [jdk.zip]");
        }
        final char[] password = Passwords.readFile(Path.of(args[2]));
        try (EncryptionRequest encryption = EncryptionRequest.builder()
                .input(Path.of(args[0]))
                .password(password)
                .progress((stage, percent) -> System.out.printf("%3d%% %s%n", percent, stage))
                .build()) {
            final LauncherRequest.Builder builder = LauncherRequest.builder()
                    .encryption(encryption)
                    .output(Path.of(args[1]))
                    .issuerFile(Path.of(args[3]))
                    .appId(args[4])
                    .target(args[5]);
            if (args.length == 7) {
                builder.jdkZip(Path.of(args[6]));
            }
            // 省略 notAfter 表示永久有效；传入 Instant 可指定不包含该时刻的到期边界。
            try (LauncherRequest request = builder.build()) {
                System.out.println(XJarPlus.packageLauncher(request).output());
            }
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
