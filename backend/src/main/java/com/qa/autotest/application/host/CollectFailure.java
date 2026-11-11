package com.qa.autotest.application.host;

import java.util.Locale;

/**
 * 采集失败原因分类（v4.12.0 / FR-v4.12-09）。
 *
 * <p>为什么非要分类：现场第一轮探测时，25G 交换机因为「只支持
 * {@code diffie-hellman-group14-sha1}」而握手失败，报出来的是
 * {@code Unable to negotiate with 10.202.54.93 port 22}——如果把它统一显示成「认证失败」，
 * 运维会去改密码，而真正要改的是 SSH 客户端参数。**归错类会把排障引到反方向。**
 *
 * <p>四类口径（PRD FR-v4.12-09）：
 * <ul>
 *   <li>{@code algorithm_mismatch} —— 算法/主机密钥协商失败，改 {@code connectOptions} 可解；</li>
 *   <li>{@code auth_failed} —— 账号口令或密钥不被接受；</li>
 *   <li>{@code command_unsupported} —— 登进去了但设备不认这条命令；</li>
 *   <li>{@code unreachable} —— 连不上、超时、无路由（兜底类）。</li>
 * </ul>
 *
 * <p>判据顺序不能乱：协商失败的报错里常含 "failed"，认证失败的报错里常含 "connection"，
 * 按「算法 → 认证 → 命令 → 不可达」从严到宽判，才不会被更宽的关键词抢先命中。
 */
public final class CollectFailure {

    public static final String ALGORITHM = "algorithm_mismatch";
    public static final String AUTH = "auth_failed";
    public static final String COMMAND = "command_unsupported";
    public static final String UNREACHABLE = "unreachable";

    private CollectFailure() {
    }

    /** 从一段错误文本判类（纯函数，可离线单测）。 */
    public static String classify(String reason) {
        String text = reason == null ? "" : reason.toLowerCase(Locale.ROOT);
        if (matches(text, "no matching key exchange", "no matching host key", "no matching cipher",
                "unable to negotiate", "kex", "host key verification failed",
                "remote host identification has changed", "no rsa host key is known",
                "algorithm negotiation fail", "无法协商", "没有匹配的密钥交换")) {
            return ALGORITHM;
        }
        if (matches(text, "permission denied", "authentication failed", "too many authentication",
                "auth fail", "unauthorized", "401", "invalid credential", "login failed",
                "认证失败", "登录失败")) {
            return AUTH;
        }
        if (matches(text, "invalid autocommand", "command not found", "ambiguous command",
                "syntax error", "unrecognized command", "unsupported", "invalid input",
                // 中文 locale 的 shell 报错（v4.12.0 W-D 现场）：远端默认 zh_CN 时
                // nvidia-smi 缺失报的是「行 1: nvidia-smi: 未找到命令」，
                // 如果只认英文 needle，这类会被兜底成 unreachable，把「没装驱动」
                // 误导成「网络不通」。采集侧已强制 LC_ALL=C，这里是第二道防线。
                "未找到命令", "找不到命令", "命令不存在", "未找到该命令", "语法错误",
                "无效的输入", "无法识别的命令")) {
            return COMMAND;
        }
        return UNREACHABLE;
    }

    /**
     * 给报告用的原因串：`<分类>: <原始首行>`。
     *
     * <p>保留原文是刻意为之——分类是给人导航的，原文才是让人能自己复核的证据。
     */
    public static String describe(HostCommandRunner.Outcome outcome) {
        if (outcome == null) {
            return UNREACHABLE + ": 采集未执行（无结果）";
        }
        String raw = outcome.reason();
        return classify(raw) + ": " + raw;
    }

    /** 仅取分类前缀（已带前缀的串不会二次加前缀）。 */
    public static String codeOf(String reasonWithCode) {
        if (reasonWithCode == null) {
            return UNREACHABLE;
        }
        int idx = reasonWithCode.indexOf(':');
        String head = idx > 0 ? reasonWithCode.substring(0, idx).trim() : reasonWithCode.trim();
        return switch (head) {
            case ALGORITHM, AUTH, COMMAND, UNREACHABLE -> head;
            default -> classify(reasonWithCode);
        };
    }

    private static boolean matches(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
