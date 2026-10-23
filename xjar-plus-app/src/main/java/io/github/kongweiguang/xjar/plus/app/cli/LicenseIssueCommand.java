/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.LicenseRequest;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * 使用绑定应用的私有凭据签发可续期许可证，无需提供归档输入。
 */
@Command(name = "issue", mixinStandardHelpOptions = true, description = "从发行方凭据签发或续期许可证。")
public final class LicenseIssueCommand implements Callable<Integer> {
    @Option(names = "--issuer-file", required = true, description = "发行方凭据文件路径。")
    private Path issuerFile;

    @Option(names = "--output", required = true, description = "新许可证输出路径。")
    private Path output;

    @Option(names = "--not-before", description = "生效时间，ISO-8601 UTC instant。")
    private Instant notBefore;

    @Option(names = "--not-after", description = "到期时间，ISO-8601 UTC instant；省略表示永久。")
    private Instant notAfter;

    /**
     * 将发行方校验和输出安全交由核心处理，统一许可证语义的唯一归属。
     */
    @Override
    public Integer call() throws Exception {
        CommandOutput.printBuild(XJarPlus.issueLicense(request()));
        return 0;
    }

    /**
     * 用户省略可选参数时保留领域层定义的默认生效时间。
     */
    LicenseRequest request() {
        final LicenseRequest.Builder builder = LicenseRequest.builder()
                .issuerFile(issuerFile)
                .output(output)
                .notAfter(notAfter);
        if (notBefore != null) {
            builder.notBefore(notBefore);
        }
        return builder.build();
    }
}
