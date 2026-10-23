/* @author kongweiguang */
package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.KeyEnvelope;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * 以独立 Java 子进程验证真实供钥交接，不使用业务凭据。
 */
public final class RuntimeBrokerProbe {
    /**
     * 保持每次子进程调用的固定测试状态，避免相互污染。
     */
    private RuntimeBrokerProbe() {
    }

    /**
     * 确认真实运行时收到公开测试密钥，同时不消耗业务标准输入。
     */
    public static void main(String[] args) throws Exception {
        UUID artifact = UUID.fromString("00112233-4455-4677-8899-aabbccddeeff");
        byte[] encoded = ByteBuffer.allocate(100)
                .put("XKE2".getBytes(StandardCharsets.US_ASCII))
                .putLong(artifact.getMostSignificantBits()).putLong(artifact.getLeastSignificantBits())
                .put(new byte[16]).putInt(600000).put(new byte[12]).put(new byte[48]).array();
        KeyEnvelope envelope = KeyEnvelope.parse(encoded);
        if (args.length != 0 && "incomplete".equals(args[0])) {
            try {
                RuntimeKeySource.resolve(envelope);
                throw new AssertionError("incomplete broker configuration accepted");
            } catch (IOException expected) {
                if (!"XJar Plus key broker environment is incomplete".equals(expected.getMessage())) {
                    throw new AssertionError("broker failure fell through to another password source", expected);
                }
            }
        } else {
            byte[] key = RuntimeKeySource.resolve(envelope);
            if (!Arrays.equals(key, new byte[32])) {
                throw new AssertionError("unexpected broker key");
            }
            Arrays.fill(key, (byte) 0);
        }
        BufferedReader applicationInput = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        if (!"application-stdin".equals(applicationInput.readLine())) {
            throw new AssertionError("runtime consumed application stdin");
        }
        System.out.println("BROKER_STDIN_OK");
    }
}
