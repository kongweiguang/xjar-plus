/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure;

/**
 * 同时供 ZIP 元数据和认证归档索引使用的流式测量结果。
 */
record EntryFacts(long length, long crc32, byte[] sha256) {
    /**
 * 复制摘要字节，因为它会作为认证元数据供后续输出阶段共用。
     */
    EntryFacts {
        sha256 = sha256.clone();
    }

    /**
 * 返回摘要的防御性副本，保持密码学索引状态不可变。
     */
    @Override
    public byte[] sha256() {
        return sha256.clone();
    }
}
