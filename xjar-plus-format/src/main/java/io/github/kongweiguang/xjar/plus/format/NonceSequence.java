/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

import java.nio.ByteBuffer;

/**
 * 在单个数据密钥的生命周期内，通过随机前缀和单调计数器分配唯一的 96 位 nonce。
 *
 * @author kongweiguang
 */
public final class NonceSequence {
    private final byte[] prefix;
    private long nextCounter;

    /**
     * 为每个产物密钥生成新的随机前缀，使 nonce 分配仅属于该密钥。
     */
    public NonceSequence() {
        this(Crypto.randomBytes(8), 0L);
    }

    /**
     * 支持确定性格式测试，同时保持与生产环境相同的计数器耗尽规则。
     */
    NonceSequence(byte[] prefix, long initialCounter) {
        if (prefix == null || prefix.length != 8) {
            throw new IllegalArgumentException("nonce prefix must contain 8 bytes");
        }
        if (initialCounter < 0 || initialCounter > 0x100000000L) {
            throw new IllegalArgumentException("initial counter is outside the uint32 sequence");
        }
        this.prefix = prefix.clone();
        this.nextCounter = initialCounter;
    }

    /**
     * 返回下一个 nonce；uint32 计数耗尽后永久失败，而不回绕并重复使用 nonce。
     */
    public synchronized byte[] next() {
        if (nextCounter > 0xffffffffL) {
            throw new IllegalStateException("nonce sequence exhausted");
        }
        final byte[] nonce = ByteBuffer.allocate(12).put(prefix).putInt((int) nextCounter).array();
        nextCounter++;
        return nonce;
    }
}
