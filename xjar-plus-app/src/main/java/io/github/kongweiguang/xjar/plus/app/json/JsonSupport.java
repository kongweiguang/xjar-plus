/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.json;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 统一 CLI 与本地网页的 JSON 边界，避免不同入口对重复键和尾随内容采用不同解释。
 */
public final class JsonSupport {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /**
     * 禁止创建实例，解析和序列化策略由不可变共享映射器集中管理。
     */
    private JsonSupport() {
    }

    /**
     * 只接受单个 JSON 对象，并将解析错误归一化，避免异常消息夹带请求正文或其中的密码。
     */
    public static JsonNode readObject(byte[] input) {
        final JsonNode value;
        try {
            value = MAPPER.readTree(input);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("JSON 格式无效。请检查重复字段、尾随内容和字段类型。");
        }
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException("JSON 请求必须是对象。");
        }
        return value;
    }

    /**
     * 只将字符串或显式 null 映射为可选文本，防止数字和布尔值被宽松转换成配置字符串。
     */
    public static String optionalText(JsonNode object, String name) {
        final JsonNode value = object.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException(name + " 必须是文本。");
        }
        return value.stringValue();
    }

    /**
     * 使用同一映射器序列化 CLI 与 HTTP 输出，且不将序列化异常转换成含敏感值的诊断信息。
     */
    public static String write(Object value, boolean pretty) {
        try {
            return pretty
                    ? MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value)
                    : MAPPER.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("JSON 输出失败。", e);
        }
    }
}
