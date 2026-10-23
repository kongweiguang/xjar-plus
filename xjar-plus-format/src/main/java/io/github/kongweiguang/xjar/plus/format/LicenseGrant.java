/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.format;

/**
 * 表示不可变的已解码许可证有效期，使用 UTC epoch 秒确保 Java 与 Go 语义一致。
 *
 * @author kongweiguang
 */
public final class LicenseGrant {
    private final String appId;
    private final long notBeforeEpochSecond;
    private final long notAfterEpochSecond;

    /**
     * 保存已校验的有效期区间，不保留签发方的加密材料。
     */
    LicenseGrant(String appId, long notBeforeEpochSecond, long notAfterEpochSecond) {
        this.appId = appId;
        this.notBeforeEpochSecond = notBeforeEpochSecond;
        this.notAfterEpochSecond = notAfterEpochSecond;
    }

    /**
     * 返回由许可证密钥认证的应用身份，供启动器与其载荷进行比对。
     */
    public String getAppId() {
        return appId;
    }

    /**
     * 返回包含在有效期内的 UTC 起始时刻，不进行时区转换。
     */
    public long getNotBeforeEpochSecond() {
        return notBeforeEpochSecond;
    }

    /**
     * 永久授权返回零；有限授权返回不包含在有效期内的 UTC 到期时刻。
     */
    public long getNotAfterEpochSecond() {
        return notAfterEpochSecond;
    }

    /**
     * 统一各运行时对左闭右开有效期区间的判断，包括起始和到期边界。
     */
    public boolean isValidAt(long epochSecond) {
        return epochSecond >= notBeforeEpochSecond
               && (notAfterEpochSecond == 0 || epochSecond < notAfterEpochSecond);
    }

    /**
     * 明确报告永久许可证标记，避免与有限的 epoch 时间戳混淆。
     */
    public boolean isPermanent() {
        return notAfterEpochSecond == 0;
    }
}
