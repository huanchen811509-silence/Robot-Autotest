package com.qa.autotest.application.host;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 采集失败原因分类（v4.12.0 / FR-v4.12-09）。
 *
 * <p>这几条用例的**输入全部是现场真实报错原文**（2026-11-11 实测所得），
 * 不是编的：25G 交换机握手失败、R750 BMC 认证被拒、无路由网段超时。
 * 分类判错的代价在评估报告里写过——把「算法不兼容」报成「认证失败」，
 * 运维会去改密码，而真正要改的是 SSH 客户端参数。
 */
class CollectFailureTest {

    @Test
    @DisplayName("算法协商失败：只支持老 KEX 的交换机（现场 10.202.54.93 原文）")
    void algorithmMismatch() {
        String real = "Unable to negotiate with 10.202.54.93 port 22: no matching key exchange "
                + "method found. Their offer: diffie-hellman-group-exchange-sha1,"
                + "diffie-hellman-group14-sha1,diffie-hellman-group1-sha1";
        assertThat(CollectFailure.classify(real)).isEqualTo(CollectFailure.ALGORITHM);
    }

    @Test
    @DisplayName("算法协商失败：主机密钥校验失败也算这一类（换机器后指纹变了）")
    void hostKeyChanged() {
        assertThat(CollectFailure.classify(
                "主机指纹校验失败（StrictHostKeyChecking=yes）：Host key verification failed."))
                .isEqualTo(CollectFailure.ALGORITHM);
    }

    @Test
    @DisplayName("认证失败：R750 BMC 与 SSH 账号被拒（现场原文）")
    void authFailed() {
        assertThat(CollectFailure.classify("eht@10.202.54.16: Permission denied (password)."))
                .isEqualTo(CollectFailure.AUTH);
        assertThat(CollectFailure.classify("auth_failed: HTTP 401（Redfish 认证被拒，账号或口令不符）"))
                .isEqualTo(CollectFailure.AUTH);
    }

    @Test
    @DisplayName("命令不支持：centecOS 收到 show interface brief（现场原文）")
    void commandUnsupported() {
        assertThat(CollectFailure.classify(
                "% Line has invalid autocommand : show interface brief"))
                .isEqualTo(CollectFailure.COMMAND);
        assertThat(CollectFailure.classify("% Ambiguous command:  \"show port\""))
                .isEqualTo(CollectFailure.COMMAND);
    }

    @Test
    @DisplayName("不可达：内网真地址无路由 / 连接超时（现场原文）")
    void unreachable() {
        assertThat(CollectFailure.classify("ssh: connect to host 172.31.20.101 port 22: "
                + "No route to host")).isEqualTo(CollectFailure.UNREACHABLE);
        assertThat(CollectFailure.classify("远程采集超时（>10s，host=10.202.54.101）"))
                .isEqualTo(CollectFailure.UNREACHABLE);
    }

    @Test
    @DisplayName("空原因兜底为 unreachable，且不抛异常")
    void nullReason() {
        assertThat(CollectFailure.classify(null)).isEqualTo(CollectFailure.UNREACHABLE);
        assertThat(CollectFailure.classify("")).isEqualTo(CollectFailure.UNREACHABLE);
    }

    @Test
    @DisplayName("describe 保留原文：分类只是导航，原文才是可复核的证据")
    void describeKeepsRawText() {
        HostCommandRunner.Outcome outcome = new HostCommandRunner.Outcome(255, "",
                "Permission denied (password).");
        String described = CollectFailure.describe(outcome);
        assertThat(described).startsWith(CollectFailure.AUTH + ": ");
        assertThat(described).contains("Permission denied");
    }

    @Test
    @DisplayName("codeOf 不会二次加前缀；已带前缀的串原样取头")
    void codeOfIsIdempotent() {
        assertThat(CollectFailure.codeOf("auth_failed: Permission denied")).isEqualTo(CollectFailure.AUTH);
        assertThat(CollectFailure.codeOf("Permission denied (password).")).isEqualTo(CollectFailure.AUTH);
        assertThat(CollectFailure.codeOf(null)).isEqualTo(CollectFailure.UNREACHABLE);
    }

    /**
     * 中文 locale 的 shell 报错（现场 storage01 原文）。
     *
     * <p>只认英文 needle 时这类会被兜底成 {@code unreachable}，把「没装 nvidia 驱动」
     * 显示成「网络不通」，运维会去查链路。采集侧已强制 {@code LC_ALL=C} 消除成因，
     * 这里锁的是第二道防线：万一某台机器不认 LC_ALL，分类也不能判反。
     */
    @Test
    @DisplayName("中文报错不被兜底成 unreachable（现场 storage01 原文）")
    void chineseMessages() {
        assertThat(CollectFailure.classify(
                "bash: 行 1: nvidia-smi: 未找到命令")).isEqualTo(CollectFailure.COMMAND);
        assertThat(CollectFailure.classify("sh: 1: nvidia-smi: 找不到命令"))
                .isEqualTo(CollectFailure.COMMAND);
        assertThat(CollectFailure.classify("认证失败")).isEqualTo(CollectFailure.AUTH);
        assertThat(CollectFailure.classify("ssh: 无法协商：没有匹配的密钥交换方法"))
                .isEqualTo(CollectFailure.ALGORITHM);
    }
}
