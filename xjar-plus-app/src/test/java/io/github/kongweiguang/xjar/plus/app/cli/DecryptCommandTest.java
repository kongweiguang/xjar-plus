/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.app.XJarPlusApplication;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecryptCommandTest {
    /**
     * 确保解密安全拒绝仅供加密使用的筛选参数，避免表现为已接受但实际未生效。
     */
    @Test
    void rejectsFilterOptionsWithoutEchoingValues() throws Exception {
        final PrintStream originalOut = System.out;
        final PrintStream originalErr = System.err;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            System.setOut(output);
            System.setErr(output);

            final int exitCode = XJarPlusApplication.execute("decrypt", "--input=encrypted.jar",
                    "--output=restored.jar", "--include=do-not-echo-secret");

            final String text = captured.toString(StandardCharsets.UTF_8);
            assertEquals(2, exitCode);
            assertTrue(text.contains("参数错误"));
            assertFalse(text.contains("do-not-echo-secret"));
            assertTrue(text.contains("decrypt"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            output.close();
        }
    }
}
