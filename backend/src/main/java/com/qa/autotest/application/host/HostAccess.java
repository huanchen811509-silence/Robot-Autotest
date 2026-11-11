package com.qa.autotest.application.host;

/**
 * 远程主机访问材料（FR-v4.2-03 / v4.12.0 扩展）。
 *
 * <p>只在服务层校验通过后构造：项目成员/凭据权限（CREDENTIAL:USE）在
 * {@code CredentialService} 里判，二次确认在 {@code HostInventoryService} 里判。
 * 采集器拿到它只做一件事——把命令送过去，不再做任何权限判断。
 *
 * <p>明文口令/私钥只活在这个对象的生命周期里：{@link #toString()} 已打码，
 * 禁止把本对象或其字段写进日志、审计 detail 或异常消息。
 *
 * @param credType       凭据类型，决定走哪条采集通道：{@code ssh}（Linux）/ {@code network}
 *                       （网络设备 CLI）/ {@code bmc}（Redfish）；null 按 {@code ssh} 处理
 * @param connectOptions SSH 客户端附加参数（现场 25G 交换机只支持
 *                       {@code diffie-hellman-group14-sha1}，不加就连不上）；由
 *                       {@code SshHostCommandRunner} 按白名单逐条转成 {@code -o}，不接受 shell 片段
 */
public record HostAccess(long projectId, String username, Long credentialId, String secret,
                         String credType, String connectOptions) {

    /** 老调用点（本机 / 只带口令的远程）：等价于 {@code ssh} 通道、无附加参数。 */
    public HostAccess(long projectId, String username, Long credentialId, String secret) {
        this(projectId, username, credentialId, secret, "ssh", null);
    }

    /**
     * 通道类型归一：空值与未知值都按 Linux SSH 走（保持 v4.2 的既有行为）。
     *
     * <p>不认识的类型宁可退化到老路径，也不抛异常——采集失败的原因应当由「连不上」
     * 或「命令不存在」给出，而不是「类型不认识」这种平台内部状态。
     */
    public String channel() {
        return channelOf(credType);
    }

    /**
     * 「凭据类型 → 采集通道」的唯一真源（v4.12.0）。
     *
     * <p>资产页要**在采集之前**就告诉操作者「这条凭据会走哪条通道」（决定按钮显隐与提示文案），
     * 那时还没有 {@code HostAccess} 实例。若让页面自己再写一份 switch，两处口径迟早分叉：
     * 页面说 bmc、实际走 ssh，用户看到的就是「明明有按钮却采不到 BMC 数据」。
     */
    public static String channelOf(String credType) {
        String value = credType == null ? "" : credType.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "network", "cli", "switch" -> "network";
            case "bmc", "redfish", "ipmi" -> "bmc";
            default -> "ssh";
        };
    }

    @Override
    public String toString() {
        return "HostAccess[projectId=" + projectId
                + ", username=" + (username == null || username.isBlank() ? "***" : username)
                + ", credentialId=" + credentialId
                + ", channel=" + channel()
                + ", connectOptions=" + (connectOptions == null ? "-" : connectOptions)
                + ", secret=***]";
    }
}
