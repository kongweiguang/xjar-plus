/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.examples;

import io.github.kongweiguang.xjar.plus.api.LicenseRequest;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 续期只使用发布者凭据，不需要客户应用或其加密口令。
 */
public final class LicenseMain {
    /**
     * 仅保留可执行入口，避免示例承担额外的实例职责。
     */
    private LicenseMain() {
    }

    /**
     * 写入新的许可证文件，避免误覆盖运行中部署正在使用的许可证。
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Usage: LicenseMain <issuer-file> <new-license-file> <expiry-UTC>");
        }
        final LicenseRequest request = LicenseRequest.builder()
                .issuerFile(Path.of(args[0]))
                .output(Path.of(args[1]))
                .notAfter(Instant.parse(args[2]))
                .build();
        System.out.println(XJarPlus.issueLicense(request).output());
    }
}
