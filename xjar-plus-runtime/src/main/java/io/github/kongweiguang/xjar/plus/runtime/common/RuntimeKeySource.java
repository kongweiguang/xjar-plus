/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.KeyEnvelope;
import io.github.kongweiguang.xjar.plus.format.Passwords;

import java.io.Console;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;

/**
 * 获取数据密钥时不读取应用 stdin，也不持久化明文密钥。
 *
 * @author kongweiguang
 */
public final class RuntimeKeySource {
    private static final String BROKER_PORT = "XJAR_PLUS_BROKER_PORT";
    private static final String BROKER_TOKEN = "XJAR_PLUS_BROKER_TOKEN";
    private static final String BROKER_ARTIFACT_ID = "XJAR_PLUS_ARTIFACT_ID";
    private static final String PASSWORD_FILE_PROPERTY = "xjar.plus.password-file";
    private static final int DATA_KEY_BYTES = 32;
    private static final int BROKER_TIMEOUT_MILLIS = 5000;

    /**
     * 避免实例化；密钥来源没有可变状态，应在每次启动时独立选择。
     */
    private RuntimeKeySource() {
    }

    /**
     * 配置了 broker 时必须使用该通道，避免交接失败后静默回退到密码；解析完成后由调用方关闭 envelope。
     */
    public static byte[] resolve(KeyEnvelope envelope) throws IOException {
        final Map<String, String> environment = System.getenv();
        final boolean brokerRequested = environment.containsKey(BROKER_PORT)
                                        || environment.containsKey(BROKER_TOKEN)
                                        || environment.containsKey(BROKER_ARTIFACT_ID);
        if (brokerRequested) {
            return requestBrokerKey(envelope, environment);
        }
        return unwrapPassword(envelope);
    }

    /**
     * 认证一次性 broker 请求，并拒绝不完整的环境配置或响应尾随字节。
     */
    private static byte[] requestBrokerKey(KeyEnvelope envelope, Map<String, String> environment) throws IOException {
        final BrokerRequest request = brokerRequest(envelope, environment);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", request.port), BROKER_TIMEOUT_MILLIS);
            socket.setSoTimeout(BROKER_TIMEOUT_MILLIS);
            socket.getOutputStream().write(request.payload);
            socket.getOutputStream().flush();
            return readBrokerKey(socket);
        }
    }

    /**
     * 在网络连接传输秘密请求前校验 broker 身份和协议长度限制。
     */
    private static BrokerRequest brokerRequest(KeyEnvelope envelope, Map<String, String> environment)
            throws IOException {
        final String portText = environment.get(BROKER_PORT);
        final String token = environment.get(BROKER_TOKEN);
        final String artifactId = environment.get(BROKER_ARTIFACT_ID);
        if (portText == null || token == null || artifactId == null) {
            throw new IOException("XJar Plus key broker environment is incomplete");
        }
        if (!artifactId.equals(envelope.getArtifactId())) {
            throw new IOException("XJar Plus key broker artifact does not match this archive");
        }
        if (!token.matches("[0-9a-fA-F]{64}")) {
            throw new IOException("XJar Plus key broker token has an invalid format");
        }
        final int port = brokerPort(portText);
        final byte[] request = (token + " " + artifactId + "\n").getBytes(StandardCharsets.UTF_8);
        if (request.length > 256) {
            throw new IOException("XJar Plus key broker request exceeds the protocol limit");
        }
        return new BrokerRequest(port, request);
    }

    /**
     * 连接前拒绝格式错误的端口，避免环境配置错误指向非预期端点。
     */
    private static int brokerPort(String portText) throws IOException {
        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException failure) {
            throw new IOException("XJar Plus key broker port is invalid", failure);
        }
        if (port < 1 || port > 65535) {
            throw new IOException("XJar Plus key broker port is outside the TCP port range");
        }
        return port;
    }

    /**
     * 从 socket 所有的通道中读取且仅读取一个密钥，在交给 AES-GCM 前拒绝尾随数据。
     */
    private static byte[] readBrokerKey(Socket socket) throws IOException {
        try (DataInputStream input = new DataInputStream(socket.getInputStream())) {
            final byte[] dataKey = new byte[DATA_KEY_BYTES];
            try {
                input.readFully(dataKey);
                if (input.read() != -1) {
                    throw new IOException("XJar Plus key broker returned trailing data");
                }
                return dataKey;
            } catch (IOException failure) {
                Arrays.fill(dataKey, (byte) 0);
                throw failure;
            }
        }
    }

    /**
     * 仅从明确指定的密码文件或 Console 读取密码，保留 stdin 供启动后的业务应用使用。
     */
    private static byte[] unwrapPassword(KeyEnvelope envelope) throws IOException {
        final String passwordFile = System.getProperty(PASSWORD_FILE_PROPERTY);
        if (passwordFile != null) {
            final char[] password = Passwords.readFile(Paths.get(passwordFile));
            return unwrapAndClear(envelope, password);
        }
        final Console console = System.console();
        if (console == null) {
            throw new IOException("No password source is available; use a password file or run from a console");
        }
        GeneralSecurityException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            final char[] password = console.readPassword("XJar Plus password: ");
            if (password == null) {
                throw new IOException("Password input was cancelled");
            }
            try {
                return envelope.unwrap(password);
            } catch (GeneralSecurityException failure) {
                lastFailure = failure;
                if (attempt < 3) {
                    console.printf("Password did not unlock this archive. Try again (%d/3).%n", attempt + 1);
                }
            } finally {
                Arrays.fill(password, '\0');
            }
        }
        throw new IOException("Unable to unlock the XJar Plus archive after three attempts", lastFailure);
    }

    /**
     * PBKDF2 派生完成后立即清除密码字符，此时应用类尚未加载。
     */
    private static byte[] unwrapAndClear(KeyEnvelope envelope, char[] password) throws IOException {
        try {
            return envelope.unwrap(password);
        } catch (GeneralSecurityException failure) {
            throw new IOException("Password did not unlock this XJar Plus archive", failure);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    /**
     * 将已校验的一次性 broker 连接数据集中保存，不创建可变共享配置状态。
     */
    private static final class BrokerRequest {
        private final int port;
        private final byte[] payload;

        /**
     * 仅为一次本地交接保留已校验的临时端口和请求字节。
         */
        private BrokerRequest(int port, byte[] payload) {
            this.port = port;
            this.payload = payload;
        }
    }
}
