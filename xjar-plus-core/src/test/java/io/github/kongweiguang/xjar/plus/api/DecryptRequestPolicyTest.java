/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证公开解密 API 会拒绝可能导致部分恢复、违背完整恢复承诺的参数。
 */
final class DecryptRequestPolicyTest {
    @TempDir
    private Path temporaryDirectory;

    /**
     * 在读取归档前拒绝包含规则，避免调用方误以为仅选定条目已恢复。
     */
    @Test
    void rejectsIncludeFilters() {
        assertRejected(builder -> builder.include("**/*.class"));
    }

    /**
     * 解密必须恢复所有通过认证的条目，因此在读取归档前拒绝排除规则。
     */
    @Test
    void rejectsExcludeFilters() {
        assertRejected(builder -> builder.exclude("**/secret/**"));
    }

    /**
     * 依赖加密选项只影响新归档的加密方式，因此解密请求必须拒绝该选项。
     */
    @Test
    void rejectsDependencyEncryptionOption() {
        assertRejected(builder -> builder.encryptDependencies(true));
    }

    /**
     * 使用确定不存在的输入路径，证明无效策略会在读取归档或创建输出前失败。
     */
    private void assertRejected(Consumer<EncryptionRequest.Builder> policy) {
        final Path output = temporaryDirectory.resolve("restored.jar");
        try (EncryptionRequest request = request(output, policy)) {
            final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> XJarPlus.decrypt(request));
            assertTrue(failure.getMessage().contains("complete original archive"));
            assertFalse(Files.exists(output));
        }
    }

    /**
     * 清除测试调用方持有的密码；请求只保留由自身管理的防御性副本。
     */
    private EncryptionRequest request(Path output, Consumer<EncryptionRequest.Builder> policy) {
        final char[] password = "test-password".toCharArray();
        try {
            final EncryptionRequest.Builder builder = EncryptionRequest.builder()
                    .input(temporaryDirectory.resolve("missing-input.jar"))
                    .output(output)
                    .password(password);
            policy.accept(builder);
            return builder.build();
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
