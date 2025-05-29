package io.xjar;

import io.xjar.key.XKey;
import org.apache.commons.codec.binary.Hex;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

    public static void make(String to, XKey xKey, String appName, String jarArgs,
                            String validStartDate, String validEndDate, String code) throws IOException {

        // 生成加密 license 文件
        license(to, xKey, code, validStartDate, validEndDate);

        // 构造变量 Map
        Map<String, String> variables = new HashMap<>();
        variables.put("validStartDate", validStartDate);
        variables.put("validEndDate", validEndDate);
        variables.put("appName", appName);
        variables.put("jarArgs", jarArgs);
        variables.put("code", code);
        variables.put("hexKey", Hex.encodeHexString(xKey.getEncryptKey()));
        variables.put("hexIV", Hex.encodeHexString(xKey.getIvParameter()));
        variables.put("xKey.algorithm", convertBytes(xKey.getAlgorithm().getBytes(StandardCharsets.UTF_8)));
        variables.put("xKey.keysize", convertBytes(String.valueOf(xKey.getKeysize()).getBytes(StandardCharsets.UTF_8)));
        variables.put("xKey.ivsize", convertBytes(String.valueOf(xKey.getIvsize()).getBytes(StandardCharsets.UTF_8)));
        variables.put("xKey.password", convertBytes(xKey.getPassword().getBytes(StandardCharsets.UTF_8)));

        // 读取模板文件并写入目标
        URL resource = XGo.class.getClassLoader().getResource(TEMPLATE_PATH);
        if (resource == null) throw new IOException("未找到模板文件: " + TEMPLATE_PATH);

        File outputFile = new File(to, MAIN_GO_FILE);
        try (
                BufferedReader reader = new BufferedReader(new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(outputFile), StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = reader.readLine()) != null) {
                for (Map.Entry<String, String> entry : variables.entrySet()) {
                    line = line.replace("#{" + entry.getKey() + "}", entry.getValue());
                }
                writer.write(line);
                writer.write(CRLF);
            }
        }
    }

    private static String convertBytes(byte[] bytes) {
        return IntStream.range(0, bytes.length)
                .mapToObj(i -> String.valueOf(bytes[i] & 0xFF))
                .collect(Collectors.joining(", "));
    }

    public static void license(String to, XKey xKey, String code, String validStartDate, String validEndDate) throws IOException {
        String json = """
                {
                    "code": "%s",
                    "validStartDate": "%s",
                    "validEndDate": "%s"
                }
                """.formatted(code, validStartDate, validEndDate);

        File outputFile = Paths.get(to, LICENSE_FILE).toFile();

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
}
