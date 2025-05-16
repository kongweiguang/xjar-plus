package io.xjar;

import io.xjar.key.XKey;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static io.xjar.XConstants.CRLF;

/**
 * XJar GoLang 启动器
 *
 * @author Payne 646742615@qq.com
 * 2020/4/6 18:20
 */
public class XGo {

    public static void make(String to, XKey xKey, String appName, String jarArgs, String validStartDate, String validEndDate) throws IOException {

        byte[] algorithm = xKey.getAlgorithm().getBytes(StandardCharsets.UTF_8);
        byte[] keysize = String.valueOf(xKey.getKeysize()).getBytes(StandardCharsets.UTF_8);
        byte[] ivsize = String.valueOf(xKey.getIvsize()).getBytes(StandardCharsets.UTF_8);
        byte[] password = xKey.getPassword().getBytes(StandardCharsets.UTF_8);

        Map<String, String> variables = new HashMap<>();
        variables.put("validStartDate", validStartDate);
        variables.put("validEndDate", validEndDate);
        variables.put("appName", appName);
        variables.put("jarArgs", jarArgs);
        variables.put("xKey.algorithm", convert(algorithm));
        variables.put("xKey.keysize", convert(keysize));
        variables.put("xKey.ivsize", convert(ivsize));
        variables.put("xKey.password", convert(password));

        String goMain = "main.go";

        URL url = XGo.class.getClassLoader().getResource("xjar/" + goMain);

        if (url == null) {
            throw new IOException("could not find xjar/" + goMain + " in classpath");
        }

        File src = new File(to, goMain);
        try (
                InputStream in = url.openStream();
                Reader reader = new InputStreamReader(in);
                BufferedReader br = new BufferedReader(reader);
                OutputStream out = new FileOutputStream(src);
                Writer writer = new OutputStreamWriter(out);
                BufferedWriter bw = new BufferedWriter(writer)
        ) {
            String line;
            while ((line = br.readLine()) != null) {
                for (Map.Entry<String, String> variable : variables.entrySet()) {
                    line = line.replace("#{" + variable.getKey() + "}", variable.getValue());
                }
                bw.write(line);
                bw.write(CRLF);
            }
            bw.flush();
            writer.flush();
            out.flush();
        }
    }

    private static String convert(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            if (!builder.isEmpty()) {
                builder.append(", ");
            }
            builder.append(b & 0xFF);
        }
        return builder.toString();
    }

}
