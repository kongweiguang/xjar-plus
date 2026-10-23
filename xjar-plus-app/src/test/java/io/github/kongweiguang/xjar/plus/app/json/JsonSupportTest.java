/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.json;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSupportTest {
    /**
     * 拒绝有歧义的 JSON，并验证解析诊断不会回显输入秘密。
     */
    @Test
    void rejectsDuplicateKeysAndTrailingValuesWithoutEchoingInput() {
        final String secret = "synthetic-secret-marker";
        for (String input : new String[]{"{\"password\":\"" + secret + "\",\"password\":\"other\"}",
                "{\"password\":\"" + secret + "\"} {}", "[\"" + secret + "\"]"}) {
            final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> JsonSupport.readObject(input.getBytes(StandardCharsets.UTF_8)));
            assertFalse(error.getMessage().contains(secret));
        }
    }

    /**
     * 验证统一序列化器将显式空字段保留为 JSON null。
     */
    @Test
    void serializesNullValues() {
        final LinkedHashMap<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("value", null);
        final String serialized = JsonSupport.write(withNull, false);
        assertTrue(JsonSupport.readObject(serialized.getBytes(StandardCharsets.UTF_8)).get("value").isNull());
    }
}
