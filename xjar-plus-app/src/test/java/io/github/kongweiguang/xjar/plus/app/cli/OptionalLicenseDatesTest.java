/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.CancellationToken;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class OptionalLicenseDatesTest {
    /**
     * 验证打包命令省略可选 --not-before 参数时保留请求构造器默认值。
     */
    @Test
    void packageRequestKeepsDomainDefaultNotBefore() {
        final PackageCommand command = new PackageCommand();
        new CommandLine(command).parseArgs("--input", "app.jar", "--output", "dist", "--app-id", "demo",
                "--issuer-file", "demo.issuer", "--target", "windows-amd64");

        try (var request = command.request(new char[]{'p'})) {
            assertNotNull(request.notBefore());
        }
    }

    /**
     * 验证打包会将会话令牌传递给启动器请求持有的归档请求。
     */
    @Test
    void packageRequestUsesProvidedCancellationToken() {
        final PackageCommand command = new PackageCommand();
        new CommandLine(command).parseArgs("--input", "app.jar", "--output", "dist", "--app-id", "demo",
                "--issuer-file", "demo.issuer", "--target", "windows-amd64");
        final var token = new CancellationToken() {
            /** 保持令牌有效，使测试可以检查请求关联而不真正启动操作。 */
            @Override
            public boolean isCancelled() {
                return false;
            }
        };

        try (var request = command.request(new char[]{'p'}, token)) {
            assertSame(token, request.encryption().cancellation());
        }
    }

    /**
     * 验证续期命令省略可选开始时间时保留许可证请求默认值。
     */
    @Test
    void issueRequestKeepsDomainDefaultNotBefore() {
        final LicenseIssueCommand command = new LicenseIssueCommand();
        new CommandLine(command).parseArgs("--issuer-file", "demo.issuer", "--output", "key.x");

        assertNotNull(command.request().notBefore());
    }
}
