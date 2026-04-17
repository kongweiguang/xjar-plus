package io.xjar;

import io.xjar.key.XKey;
import org.apache.commons.codec.binary.Hex;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.xjar.XConstants.CRLF;

/**
 * XJar GoLang 启动器
 *
 * @author kongweiguang
 */
public class XGo {

    private static final String MAIN_GO_FILE = "main.go";
    private static final String LICENSE_FILE = "key.x";
    private static final String TEMPLATE_PATH = "xjar/" + MAIN_GO_FILE;

    public static void make(String to,
                            XKey xKey,
                            String code,
                            String appName,
                            String jarArgs,
                            String validStartDate,
                            String validEndDate) throws IOException {

        // 生成加密 license 文件
        license(to, xKey, code, "", validStartDate, validEndDate);

        // 构造变量 Map
        Map<String, String> variables = new HashMap<>();
        variables.put("validStartDate", escapeGoString(validStartDate));
        variables.put("validEndDate", escapeGoString(validEndDate));
        variables.put("appName", escapeGoString(appName));
        variables.put("jarArgs", escapeGoString(jarArgs));
        variables.put("code", escapeGoString(code));
        variables.put("hexKey", Hex.encodeHexString(xKey.getEncryptKey()));
        variables.put("hexIV", Hex.encodeHexString(xKey.getIvParameter()));
        variables.put("xKey.algorithm", convertBytes(xKey.getAlgorithm().getBytes(StandardCharsets.UTF_8)));
        variables.put("xKey.keysize", convertBytes(String.valueOf(xKey.getKeysize()).getBytes(StandardCharsets.UTF_8)));
        variables.put("xKey.ivsize", convertBytes(String.valueOf(xKey.getIvsize()).getBytes(StandardCharsets.UTF_8)));
        variables.put("xKey.password", convertBytes(xKey.getPassword().getBytes(StandardCharsets.UTF_8)));

        // 读取模板文件并写入目标
        InputStream resource = XGo.class.getClassLoader().getResourceAsStream(TEMPLATE_PATH);
        if (resource == null) {
            throw new IOException("未找到模板文件: " + TEMPLATE_PATH);
        }

        Path outputDir = Paths.get(to);
        Files.createDirectories(outputDir);
        Path outputFile = outputDir.resolve(MAIN_GO_FILE);
        try (resource) {
            String template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            for (Map.Entry<String, String> entry : variables.entrySet()) {
                template = template.replace("#{" + entry.getKey() + "}", entry.getValue());
            }
            template = template.replace("\r\n", "\n").replace("\r", "\n");
            Files.writeString(outputFile, template.replace("\n", CRLF), StandardCharsets.UTF_8);
        }
    }

    private static String convertBytes(byte[] bytes) {
        return IntStream.range(0, bytes.length)
                .mapToObj(i -> String.valueOf(bytes[i] & 0xFF))
                .collect(Collectors.joining(", "));
    }

    private static String escapeGoString(String value) {
        return value == null ? "" : value
                                    .replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\r", "\\r")
                                    .replace("\n", "\\n")
                                    .replace("\t", "\\t");
    }

    /**
     * 生成加密 license 文件
     *
     * @param to             目标路径
     * @param xKey           加密密钥
     * @param code           启动器 code
     * @param args           额外启动器参数
     * @param validStartDate 启动器有效期开始时间
     * @param validEndDate   启动器有效期结束时间
     * @throws IOException 加密写入 license 文件失败
     */
    public static void license(String to,
                               XKey xKey,
                               String code,
                               String args,
                               String validStartDate,
                               String validEndDate) throws IOException {
        String json = """
                {
                    "code": %s,
                    "validStartDate": %s,
                    "validEndDate": %s,
                    "args": %s
                }
                """.formatted(toJsonString(code), toJsonString(validStartDate), toJsonString(validEndDate), toJsonString(args));

        Path outputDir = Paths.get(to);
        Files.createDirectories(outputDir);
        File outputFile = outputDir.resolve(LICENSE_FILE).toFile();

        try (
                OutputStream fileOut = new FileOutputStream(outputFile)
        ) {
            Cipher cipher = Cipher.getInstance(xKey.getAlgorithm());
            SecretKeySpec keySpec = new SecretKeySpec(xKey.getEncryptKey(), xKey.getAlgorithm().split("/")[0]);
            IvParameterSpec ivSpec = new IvParameterSpec(xKey.getIvParameter());
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec);

            try (CipherOutputStream cipherOut = new CipherOutputStream(fileOut, cipher)) {
                cipherOut.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new IOException("加密写入 license 文件失败", e);
        }
    }

    private static String toJsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder builder = new StringBuilder(value.length() + 2);
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        builder.append(String.format("\\u%04x", (int) ch));
                    } else {
                        builder.append(ch);
                    }
                }
            }
        }
        return builder.append('"').toString();
    }
}
