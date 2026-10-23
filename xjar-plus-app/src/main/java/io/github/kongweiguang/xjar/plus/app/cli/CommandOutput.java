/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.cli;

import io.github.kongweiguang.xjar.plus.api.ArchiveInfo;
import io.github.kongweiguang.xjar.plus.api.BuildResult;
import io.github.kongweiguang.xjar.plus.app.json.JsonSupport;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 集中管理输出格式，确保 CLI 与 UI 接口对外呈现一致的核心结果。
 */
public final class CommandOutput {
    /**
     * 禁止创建有状态实例，因为所有轻量命令共用同一输出格式策略。
     */
    private CommandOutput() {
    }

    /**
     * 仅输出公开的构建元数据，不序列化秘密或请求对象。
     */
    public static void printBuild(BuildResult result) {
        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("output", result.output().toString());
        fields.put("artifactId", result.artifactId());
        fields.put("warnings", result.warnings());
        System.out.println(JsonSupport.write(fields, true));
    }

    /**
     * 输出核心归档分类信息，不暴露内部密码学材料。
     */
    public static void printArchive(ArchiveInfo info) {
        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("kind", info.kind());
        fields.put("mainClass", info.mainClass());
        fields.put("encrypted", info.encrypted());
        fields.put("entries", info.entries());
        fields.put("minimumJava", info.minimumJava());
        if (info.encrypted() && info.minimumJava() == null) {
            fields.put("minimumJavaWarning", "unknown until verify");
        }
        System.out.println(JsonSupport.write(fields, true));
    }
}
