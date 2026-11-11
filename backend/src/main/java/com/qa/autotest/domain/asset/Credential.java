package com.qa.autotest.domain.asset;

import java.time.Instant;

/**
 * 凭据视图（FR-v0.2-13）：对外<b>永不回显明文或密文</b>，只返回「是否已配置」与修改时间。
 *
 * <p>{@code cipherBlob} 只存在于仓储内部记录，不出应用层。
 *
 * @param connectOptions SSH 客户端附加参数（v4.12.0 / FR-v4.12-04）。现场 25G 交换机只支持
 *                       {@code diffie-hellman-group14-sha1}，现代 OpenSSH 默认会握手失败；
 *                       参数按凭证存，不能全局写死，否则换成新设备会反向失败。
 *                       只允许 {@code Key=Value} 形式，禁止 shell 片段（由执行层白名单校验）。
 */
public record Credential(
        Long id,
        Long projectId,
        String name,
        String credType,
        String username,
        boolean configured,
        int keyVersion,
        Instant lastRotatedAt,
        String connectOptions) {

    /** 历史调用点（不含连接参数）：保持既有构造签名不变，避免为一列改遍全仓测试夹具。 */
    public Credential(Long id, Long projectId, String name, String credType, String username,
                      boolean configured, int keyVersion, Instant lastRotatedAt) {
        this(id, projectId, name, credType, username, configured, keyVersion, lastRotatedAt, null);
    }
}
