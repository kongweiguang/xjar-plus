/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.ArchiveInfo;
import io.github.kongweiguang.xjar.plus.app.json.JsonSupport;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandOutputTest {
    /**
     * 明确显示加密归档检查结果未知，同时将数值字段保留为机器可读的 JSON null。
     */
    @Test
    void encryptedInspectShowsUnknownUntilVerified() throws Exception {
        final PrintStream originalOut = System.out;
        final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        final PrintStream output = new PrintStream(captured, true, StandardCharsets.UTF_8);
        try {
            System.setOut(output);

            CommandOutput.printArchive(new ArchiveInfo("plain", "example.Main", true, 3, null));

            final JsonNode json = JsonSupport.readObject(captured.toString(StandardCharsets.UTF_8)
                    .getBytes(StandardCharsets.UTF_8));
            assertTrue(json.get("minimumJava").isNull());
            assertEquals("unknown until verify", json.get("minimumJavaWarning").stringValue());
        } finally {
            System.setOut(originalOut);
            output.close();
        }
    }
}
