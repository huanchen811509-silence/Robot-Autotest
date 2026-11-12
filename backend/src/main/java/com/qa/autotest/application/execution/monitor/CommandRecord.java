package com.qa.autotest.application.execution.monitor;

import com.qa.autotest.application.execution.EvidenceLogWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 命令集合的一条记录（FR-v5.3-02 / W53-02）。
 *
 * <p>字段与 PRD R1 一致：{@code cmd_id / run_id / node_id / seq / endpoint / kind / argv /
 * started_at / duration_ms / exit_code / stdout_sha / truncated / sensitive}。
 * <b>口径偏差记录（两处）</b>：
 * <ol>
 *   <li>PRD 正文写「十一字段」但实列 13 项，实现按实列的 13 项做（少存字段不可逆，多存可裁）；</li>
 *   <li>R4 要求「按用例过滤」，但 R1 的 13 项里没有用例标识，无法实现该查询。
 *       因此补 {@code case_code} 一项（共 14 项）。补字段是为了让 R4 成立，
 *       不是为了多存东西——没有它，「某条用例发了哪些命令」这个最常用的问法就答不了。</li>
 * </ol>
 *
 * <p><b>原文不入库</b>（R5）：{@code argv} 进来先过 {@link EvidenceLogWriter#scrub}，
 * 再把每个参数截到 {@link #MAX_ARG_CHARS}；{@code stdout_sha} 只存哈希。
 * 这样即使有人拿到库导出，也拿不到完整命令输出，更拿不到口令。
 *
 * <p><b>敏感项只存掩码</b>（R3）：命中词表时 {@code sensitive=true}，
 * {@code argv} 里命中的那个参数整段替换为 {@code ***}——不是「部分遮挡」，
 * 因为部分遮挡在 {@code password=abc123} 这类形态下等于没遮。
 */
public record CommandRecord(String cmdId, String runId, String nodeId, String caseCode,
                            long seq, String endpoint, CommandKind kind, List<String> argv,
                            Instant startedAt, Long durationMs, Integer exitCode,
                            String stdoutSha, boolean truncated, boolean sensitive) {

    /** 单个参数最多保留的字符数。命令参数超长基本是 base64/证书，保留也无用。 */
    public static final int MAX_ARG_CHARS = 200;

    /** 入库查询返回的预览长度（R5：前 200 字符 + 指纹）。 */
    public static final int PREVIEW_CHARS = 200;

    /** 敏感词表：命中即整段掩码。 */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key"
                    + "|community|snmp|credential|passphrase)");

    /**
     * 「后跟一个值」的凭据开关。这类开关的值是**独立的 argv 元素**
     * （{@code -p Sup3rSecret}），靠单参数正则根本认不出来——正则只看到 {@code -p} 和
     * {@code Sup3rSecret} 两个互不相干的字符串。必须按位置取下一个参数。
     *
     * <p>{@code -p} 后跟纯数字时不掩码：{@code ssh -p 2222} 里那是端口。
     */
    private static final java.util.Set<String> VALUE_FLAGS = java.util.Set.of(
            "-p", "--password", "--passwd", "--token", "--secret", "--community",
            "--passphrase", "--api-key", "--api_key", "--access-key");

    /**
     * 造一条记录：脱敏 + 判类 + 算指纹。
     *
     * @param rawArgv 原始 argv（可为空，空则整条记 {@code other} 且 argv 为空列表）
     */
    public static CommandRecord of(String runId, String nodeId, String caseCode, long seq,
                                   String endpoint,
                                   List<String> rawArgv, Instant startedAt, Long durationMs,
                                   Integer exitCode, String stdout) {
        List<String> masked = new ArrayList<>();
        boolean sensitive = false;
        if (rawArgv != null) {
            List<String> args = rawArgv;
            for (int i = 0; i < args.size(); i++) {
                String arg = args.get(i);
                if (arg == null) {
                    continue;
                }
                // ① 开关 + 值分成两个参数：值那一位直接掩码
                if (VALUE_FLAGS.contains(arg.toLowerCase(java.util.Locale.ROOT))
                        && i + 1 < args.size() && args.get(i + 1) != null
                        && !args.get(i + 1).matches("\\d+")) {
                    masked.add(arg);
                    masked.add("***");
                    sensitive = true;
                    i++;
                    continue;
                }
                // ② 开关与值合写（-pXXX / --password=XXX）：靠形态正则
                if (SensitiveMask.looksSensitive(arg)) {
                    sensitive = true;
                }
                // 两道串联：先抹「凭据形态」，再按值抹（SecretRedactor）
                String scrubbed = EvidenceLogWriter.scrub(SensitiveMask.mask(arg));
                if (!scrubbed.equals(arg)) {
                    // 任一道改动了内容，说明原本就带凭据
                    sensitive = true;
                }
                masked.add(truncate(scrubbed));
            }
        }
        String cmdId = runId + "#" + seq;
        return new CommandRecord(cmdId, runId, nodeId, caseCode, seq, endpoint,
                CommandKind.classify(masked), List.copyOf(masked), startedAt, durationMs,
                exitCode, sha(stdout == null ? "" : stdout), false, sensitive);
    }

    /** 命令原文（已脱敏），给页面/diff 用。敏感项落地时就是 {@code ***}，不会二次泄漏。 */
    public String commandLine() {
        return String.join(" ", argv);
    }

    /** 入库查询预览：前 {@value #PREVIEW_CHARS} 字符 + 指纹。 */
    public String preview() {
        String line = commandLine();
        String head = line.length() <= PREVIEW_CHARS ? line : line.substring(0, PREVIEW_CHARS);
        return head + "…[" + stdoutSha + "]";
    }

    /** 两次 run 的命令是否「实质相同」：比对 argv 与 kind，不比时间与退出码。 */
    public boolean sameShape(CommandRecord other) {
        return other != null && kind == other.kind && argv.equals(other.argv);
    }

    private static String truncate(String value) {
        String text = value == null ? "" : value;
        return text.length() <= MAX_ARG_CHARS ? text : text.substring(0, MAX_ARG_CHARS) + "…";
    }

    /** 输出指纹：SHA-256 前 16 位十六进制。原文在证据文件里。 */
    static String sha(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(bytes[i] & 0xF, 16));
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
