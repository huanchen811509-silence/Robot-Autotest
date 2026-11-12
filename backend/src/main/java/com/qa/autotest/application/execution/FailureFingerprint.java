package com.qa.autotest.application.execution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 归因指纹（FR-v5.3-06 / 验收 C16–C18 / W53-06）。
 *
 * <p>用途只有一个：把「同一类失败」归到一个 key 上，好让处置动作一次覆盖一片。
 * 429 条用例都因为同一个 SSH 口令错而红时，业务上只有**一件事**要处理；
 * 逐条处置是把一件事重复 429 次，实际结果是没人处置。
 *
 * <p><b>为什么必须归一化原文</b>：同一条失败在不同实例上会带上不同数字
 * （端口号、耗时、字节数、IP 末段），不抹掉这些差异，指纹就一条也聚不起来。
 * 抹法是「数字→#、IP→#、路径保留末段」，都是确定性规则，不做语义猜测。
 */
public final class FailureFingerprint {

    private FailureFingerprint() {
    }

    /** 指纹长度：SHA-256 十六进制前 32 位。够用且比全 64 位省索引。 */
    private static final int LENGTH = 32;

    /**
     * 计算指纹。
     *
     * @param kernel   内核（同一失败在 RF 与 pytest 下的位置表达不同，指纹要分开）
     * @param category 归因分类
     * @param location 归因位置（{@code file:line} 或步骤名）
     * @param original 失败原文
     */
    public static String of(String kernel, String category, String location, String original) {
        String normalized = String.join("\u0001",
                safe(kernel).toLowerCase(Locale.ROOT),
                safe(category).toUpperCase(Locale.ROOT),
                normalizeLocation(location),
                normalizeMessage(original));
        return sha256(normalized).substring(0, LENGTH);
    }

    /**
     * 位置归一：只保留文件名与行号，去掉目录前缀。
     *
     * <p>同一份脚本在不同机器上的绝对路径不同，保留全路径会让指纹永远对不上；
     * 而文件名 + 行号已经足够定位。
     */
    static String normalizeLocation(String location) {
        if (location == null || location.isBlank()) {
            return "";
        }
        String value = location.trim().replace('\\', '/');
        int slash = value.lastIndexOf('/');
        String shortName = slash >= 0 ? value.substring(slash + 1) : value;
        return shortName.toLowerCase(Locale.ROOT);
    }

    /**
     * 原文归一：抹掉会随实例变化的部分。
     *
     * <p>顺序重要——先抹 IP（含点号的数字串），再抹小数，最后抹整数；
     * 反过来做会把 {@code 10.202.54.201} 抹成 {@code #.#.#.#}，也能对上，
     * 但会让「不同网段的同类失败」被错误合并（其实应合并，只是可读性差）。
     * 这里选择先抹 IP，是为了让 {@code 10.202.54.201} 与 {@code 192.168.1.7}
     * 落到同一个 {@code #}，符合「同一类失败」的业务语义。
     *
     * <p><b>最后一步整数替换千万不能带 {@code \b}（踩过）</b>：
     * {@code \b\d+\b} 在 {@code 5000ms} 上匹配不到——{@code 0} 与 {@code m}
     * 都是词字符，中间没有词边界。而失败原文里的数字绝大多数带单位
     * （{@code 5000ms}、{@code 12.5Gbps}、{@code 3次}），于是「抹数字」整体失效，
     * 指纹一条也聚不起来，处置功能等于没做。改成不带边界的 {@code \d+} 后，
     * {@code timed out after 5000ms} 与 {@code timed out after 9000ms} 才落到同一个指纹。
     *
     * <p>副作用是型号里的数字也会被抹（{@code ConnectX-7} → {@code connectx-#}），
     * 因此位置相同、分类相同、仅型号号位不同的失败会被并成一类。这是刻意的取舍：
     * 归一的目的是「一次认领覆盖一片」，宁可略微过度合并，也不能因为过度精细而一条都聚不上。
     */
    static String normalizeMessage(String original) {
        if (original == null || original.isBlank()) {
            return "";
        }
        // 只取首行：堆栈与后续上下文几乎每条都不同，带上就等于不归一
        String firstLine = original.strip().lines().findFirst().orElse("");
        String value = firstLine
                .replaceAll("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b", "#")
                .replaceAll("\\d+\\.\\d+", "#")
                .replaceAll("\\d+", "#")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
        return value;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必带算法，走到这里说明 JRE 被裁剪过——属于环境错误
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
