package io.github.kongweiguang.xjar.plus.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 不启动框架样本或子 JVM，单独校验重试条件。
 *
 * @author kongweiguang
 */
final class RunningServerRetryTest {
    /**
     * 仅接受明确的地址占用或所选端口已占用证据，避免因其他启动错误而重试。
     */
    @Test
    void recognizesOnlyExplicitPortOccupation() {
        assertTrue(ExecutableJarCompatibilityTest.RunningServer.hasPortCollisionEvidence(
                "Web server failed to start. Port 50769 was already in use.", 50769));
        assertTrue(ExecutableJarCompatibilityTest.RunningServer.hasPortCollisionEvidence(
                "java.net.BindException: Address already in use: bind", 50769));
        assertFalse(ExecutableJarCompatibilityTest.RunningServer.hasPortCollisionEvidence(
                "java.net.BindException: Cannot assign requested address", 50769));
        assertFalse(ExecutableJarCompatibilityTest.RunningServer.hasPortCollisionEvidence(
                "Port 50768 is already in use.", 50769));
    }

    /**
     * 将重试限定于已退出的子进程和固定次数，使无关启动错误不会被掩盖。
     */
    @Test
    void requiresExitedChildAndStopsAfterThreeReplacements() {
        assertTrue(ExecutableJarCompatibilityTest.RunningServer.shouldRetryPortCollision(true, true, 0));
        assertFalse(ExecutableJarCompatibilityTest.RunningServer.shouldRetryPortCollision(false, true, 0));
        assertFalse(ExecutableJarCompatibilityTest.RunningServer.shouldRetryPortCollision(true, false, 0));
        assertFalse(ExecutableJarCompatibilityTest.RunningServer.shouldRetryPortCollision(
                true, true, 3));
    }
}
