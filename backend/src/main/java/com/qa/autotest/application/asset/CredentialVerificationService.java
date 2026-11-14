package com.qa.autotest.application.asset;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.SecretRedactor;
import com.qa.autotest.common.tenant.TenantContext;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 凭证连通性验证（v5.7.0 / W57-38、39、40）。
 *
 * <p>核心口径：<b>只探测不采集</b>。verify 只做一次带超时的 TCP 连通性探测（≤10s），
 * 不跑任何采集命令、不回传主机清单；成功与失败都写 {@code credential_verification}
 * （只增不改的事实表），失败四分类 AUTH / TIMEOUT / UNREACHABLE / REJECTED。
 *
 * <p>安全口径：{@code detail} 只写分类、耗时、掩码后的目标，并经过
 * {@link SecretRedactor#redact} + 形态级二次脱敏；<b>禁止写口令、私钥、命令原文</b>。
 * 一次性凭证（POST body、不落库）不写本表。
 *
 * <p>AUTH 分类的边界要如实说明：默认 TCP 探测器只能区分 TIMEOUT / UNREACHABLE / REJECTED，
 * 判断不了「账号口令错」——那需要真正的 SSH 认证握手。AUTH 留给认证型探测器（或注入式
 * probe）给出；本类把它作为合法 {@code failureKind} 接收、落库、排序，不假装 TCP 能判认证。
 *
 * <p>所有 SQL 参数绑定；{@link #query} / {@link #execute} 为 package-private 通道，便于
 * 单测用 Mockito spy 覆盖，不需要真实 DB / 真实 SSH。
 */
@Service
public class CredentialVerificationService {

    /** 探测硬上限 8s，满足「超时 ≤ 10s」。 */
    private static final int PROBE_TIMEOUT_MS = 8000;

    /** options 超过 50 条时截断，并支持 keyword 服务端搜索。 */
    private static final int OPTIONS_LIMIT = 50;

    private static final String SQL_DEVICE_TARGET = """
            SELECT management_ip, management_ip_alt, bmc_address, bmc_address_alt
              FROM device WHERE id = ?
            """;

    private static final String SQL_INSERT = """
            INSERT INTO credential_verification
                (credential_id, device_id, channel, result, failure_kind, duration_ms, detail,
                 created_by, updated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SQL_LATEST = """
            SELECT id, credential_id, device_id, channel, result, failure_kind, duration_ms,
                   detail, verified_at
              FROM credential_verification ORDER BY verified_at DESC, id DESC LIMIT 1
            """;

    private static final String SQL_LATEST_PAIR = """
            SELECT id, credential_id, device_id, channel, result, failure_kind, duration_ms,
                   detail, verified_at
              FROM credential_verification
             WHERE credential_id = ? AND device_id = ?
             ORDER BY verified_at DESC, id DESC LIMIT 1
            """;

    private static final String SQL_UPDATE_DEVICE_CHECK = """
            UPDATE device_check SET credential_id = ?, updated_at = NOW(3), updated_by = ?
             WHERE device_id = ? AND credential_id IS NULL
             ORDER BY checked_at DESC, id DESC LIMIT 1
            """;

    private static final String SQL_OPTIONS = """
            SELECT c.id, c.name, c.cred_type, c.username,
                   (SELECT COUNT(*) FROM device d
                     WHERE d.management_credential_id = c.id OR d.bmc_credential_id = c.id)
                       AS bound_devices,
                   (SELECT MAX(cv.verified_at) FROM credential_verification cv
                     WHERE cv.credential_id = c.id AND cv.result = 'SUCCESS' AND cv.device_id = ?)
                       AS this_device_at,
                   (SELECT MAX(cv.verified_at) FROM credential_verification cv
                     WHERE cv.credential_id = c.id AND cv.result = 'SUCCESS'
                       AND cv.device_id IN (
                            SELECT d2.id FROM device d2 WHERE d2.id <> ?
                              AND ((d2.model IS NOT NULL
                                    AND d2.model = (SELECT model FROM device WHERE id = ?))
                                OR (d2.domain_tag IS NOT NULL
                                    AND d2.domain_tag = (SELECT domain_tag FROM device WHERE id = ?)))
                       )) AS peer_at,
                   (SELECT cv.result FROM credential_verification cv
                     WHERE cv.credential_id = c.id ORDER BY cv.verified_at DESC, cv.id DESC LIMIT 1)
                       AS last_result,
                   (SELECT MAX(cv.verified_at) FROM credential_verification cv
                     WHERE cv.credential_id = c.id) AS last_verified_at,
                   CASE WHEN c.secret_ref IS NOT NULL AND c.secret_ref <> '' THEN 1 ELSE 0 END
                       AS configured
              FROM credential c
             WHERE c.owner_scope = 'PLATFORM' AND c.asset_status <> 'deprecated'
            """;

    private static final String SQL_OPTIONS_KEYWORD =
            " AND (c.name LIKE ? OR c.username LIKE ?)";

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    /** 单一公开构造器（构造器注入，禁字段注入）。 */
    public CredentialVerificationService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- verify

    /** 探测一条凭据在某台设备上是否可用；成功失败都落库，返回脱敏后的结论。 */
    public VerificationResult verify(long assetId, long credId, String channel) {
        String normalized = normalizeChannel(channel);
        String target = targetOf(assetId);
        if (target.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "该设备没有可验证的管理地址");
        }
        if (isLocalTarget(target)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "本机目标不允许验证凭据");
        }

        long start = System.nanoTime();
        ProbeOutcome outcome;
        try {
            outcome = probe(assetId, credId, normalized);
        } catch (RuntimeException ex) {
            // 异常消息绝不外抛原文：只归类，不带任何可能夹带的敏感片段
            outcome = new ProbeOutcome(false, "REJECTED", ex.getClass().getSimpleName());
        }
        long durationMs = Math.min((System.nanoTime() - start) / 1_000_000L, PROBE_TIMEOUT_MS);
        String kind = outcome.success() ? null : outcome.failureKind();
        String detail = safeDetail(normalized, outcome, durationMs, target);

        insertVerification(credId, assetId, normalized, outcome.success(), kind,
                durationMs, detail);
        audit.record("credential.verify", "credential", String.valueOf(credId),
                "deviceId=" + assetId + "; channel=" + normalized + "; result="
                        + (outcome.success() ? "SUCCESS" : "FAILED") + "; kind="
                        + (kind == null ? "-" : kind));
        return new VerificationResult(credId, assetId, normalized, outcome.success(), kind,
                durationMs);
    }

    // ---------------------------------------------------------------- 最近一次

    /** 全局最近一条验证记录（列表页「最近验证」用）。 */
    public Optional<VerificationRecord> latest() {
        List<VerificationRecord> rows = query(SQL_LATEST, List.of()).stream()
                .map(CredentialVerificationService::recordOf).toList();
        return rows.stream().findFirst();
    }

    /** 指定「凭据 × 设备」的最近一条验证记录。 */
    public Optional<VerificationRecord> latest(long credentialId, long deviceId) {
        List<VerificationRecord> rows = query(SQL_LATEST_PAIR,
                Arrays.asList(credentialId, deviceId)).stream()
                .map(CredentialVerificationService::recordOf).toList();
        return rows.stream().findFirst();
    }

    // ---------------------------------------------------------------- 采集成功补记

    /**
     * 采集成功自动补记（W57-40）：写一条 SUCCESS 验证事实，并把本次用到的 credential.id
     * 回填到该设备最近一条尚未记凭证的 {@code device_check}（V11_64 的 credential_id 列），
     * 形成「哪条口令真能连上这台设备」的闭环。不写任何明文。
     */
    public void recordFromCollect(long deviceId, long credentialId, String channel) {
        String normalized = normalizeChannel(channel);
        insertVerification(credentialId, deviceId, normalized, true, null, 0L,
                "source=collect; channel=" + normalized);
        execute(SQL_UPDATE_DEVICE_CHECK,
                Arrays.asList(credentialId, TenantContext.userId(), deviceId));
        audit.record("credential.verify.collect", "credential", String.valueOf(credentialId),
                "deviceId=" + deviceId + "; channel=" + normalized + "; source=collect");
    }

    // ---------------------------------------------------------------- 凭据下拉 options

    /**
     * 设备选凭证下拉（W57-39）：四级排序
     * 本设备验证成功 &gt; 同型号/同域成功 &gt; 已配置 &gt; 其它；
     * 返回最近验证时间 / 结果 / 绑定设备数。&gt;50 条时截断并提供 keyword 服务端搜索。
     */
    public CredentialOptions options(long assetId, String keyword) {
        List<Object> args = new ArrayList<>(Arrays.asList(assetId, assetId, assetId, assetId));
        String sql = SQL_OPTIONS;
        if (keyword != null && !keyword.isBlank()) {
            sql = sql + SQL_OPTIONS_KEYWORD;
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        List<CredentialOption> items = new ArrayList<>();
        for (Map<String, Object> row : query(sql, args)) {
            items.add(optionOf(row));
        }
        Comparator<String> byTimeDesc = Comparator.nullsFirst(Comparator.<String>reverseOrder());
        Comparator<String> byName = Comparator.nullsLast(Comparator.<String>naturalOrder());
        items.sort(Comparator.comparingInt(CredentialOption::tier)
                .thenComparing(CredentialOption::lastVerifiedAt, byTimeDesc)
                .thenComparing(CredentialOption::name, byName));
        int total = items.size();
        boolean truncated = total > OPTIONS_LIMIT;
        List<CredentialOption> visible = truncated
                ? List.copyOf(items.subList(0, OPTIONS_LIMIT)) : List.copyOf(items);
        return new CredentialOptions(visible, total, truncated);
    }

    // ---------------------------------------------------------------- JDBC 通道（package-private，单测 spy 覆盖）

    List<Map<String, Object>> query(String sql, List<Object> args) {
        return jdbc.queryForList(sql, args.toArray());
    }

    int execute(String sql, List<Object> args) {
        return jdbc.update(sql, args.toArray());
    }

    String targetOf(long deviceId) {
        List<Map<String, Object>> rows = query(SQL_DEVICE_TARGET, Arrays.asList(deviceId));
        if (rows.isEmpty()) {
            return "";
        }
        Map<String, Object> row = rows.get(0);
        for (String column : List.of("management_ip", "management_ip_alt",
                "bmc_address", "bmc_address_alt")) {
            Object value = row.get(column);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    /** 默认探测器：只做 TCP 连通性，超时 8s，不采集、不认证。单测可 spy 覆盖返回四类失败。 */
    ProbeOutcome probe(long deviceId, long credentialId, String channel) {
        String host = targetOf(deviceId);
        int port = "BMC".equals(channel) ? 443 : 22;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PROBE_TIMEOUT_MS);
            return new ProbeOutcome(true, null, "tcp-ok");
        } catch (SocketTimeoutException ex) {
            return new ProbeOutcome(false, "TIMEOUT", "connect timeout");
        } catch (UnknownHostException | ConnectException | NoRouteToHostException ex) {
            return new ProbeOutcome(false, "UNREACHABLE", "unreachable");
        } catch (IOException ex) {
            return new ProbeOutcome(false, "REJECTED", "rejected");
        }
    }

    private void insertVerification(long credId, long deviceId, String channel, boolean success,
                                    String failureKind, long durationMs, String detail) {
        execute(SQL_INSERT, Arrays.asList(credId, deviceId, channel,
                success ? "SUCCESS" : "FAILED", failureKind, (int) durationMs, detail,
                TenantContext.userId(), TenantContext.userId()));
    }

    /** detail 双保险脱敏：先按已知值抹，再按凭据形态抹；绝不写原始异常正文里的口令。 */
    static String safeDetail(String channel, ProbeOutcome outcome, long durationMs, String target) {
        String raw = "channel=" + channel
                + "; result=" + (outcome.success() ? "SUCCESS" : "FAILED")
                + "; kind=" + (outcome.failureKind() == null ? "-" : outcome.failureKind())
                + "; ms=" + durationMs
                + "; target=" + maskHost(target)
                + "; info=" + (outcome.rawMessage() == null ? "-" : outcome.rawMessage());
        String redacted = SecretRedactor.redact(raw);
        return redacted.replaceAll(
                "(?i)((?:password|passwd|pwd|secret|token|private[_-]?key)\\s*[=:]\\s*)(\\S+)",
                "$1***");
    }

    /** 同 HostInventoryService#maskHost 口径：IPv4 留首尾段，主机名留前两位。 */
    private static String maskHost(String host) {
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            return parts[0] + ".***.***." + parts[3];
        }
        int keep = Math.min(2, host.length());
        return host.substring(0, keep) + "***";
    }

    /** 本机目标直接拒绝（与 LocalHostCommandRunner 口径一致，但不跨包引用其 package-private 方法）。 */
    static boolean isLocalTarget(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String value = host.trim().toLowerCase(Locale.ROOT);
        return value.equals("localhost") || value.equals("localhost.localdomain")
                || value.startsWith("127.") || value.equals("::1")
                || value.equals("0:0:0:0:0:0:0:1") || value.equals("0.0.0.0");
    }

    private static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return "MGMT";
        }
        String value = channel.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "MGMT", "BMC", "SSH" -> value;
            default -> "MGMT";
        };
    }

    private static CredentialOption optionOf(Map<String, Object> row) {
        long id = number(row.get("id")).longValue();
        int bound = number(row.get("bound_devices")).intValue();
        boolean configured = number(row.get("configured")).intValue() != 0;
        String thisAt = str(row.get("this_device_at"));
        String peerAt = str(row.get("peer_at"));
        int tier = thisAt != null ? 0 : peerAt != null ? 1 : configured ? 2 : 3;
        return new CredentialOption(id, str(row.get("name")), str(row.get("cred_type")),
                str(row.get("username")), tier, str(row.get("last_result")),
                str(row.get("last_verified_at")), bound, configured);
    }

    private static VerificationRecord recordOf(Map<String, Object> row) {
        Object duration = row.get("duration_ms");
        Integer ms = duration instanceof Number number ? number.intValue() : null;
        return new VerificationRecord(number(row.get("id")).longValue(),
                number(row.get("credential_id")).longValue(),
                number(row.get("device_id")).longValue(), str(row.get("channel")),
                str(row.get("result")), str(row.get("failure_kind")), ms,
                str(row.get("detail")), str(row.get("verified_at")));
    }

    private static Number number(Object value) {
        return value instanceof Number number ? number : 0;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ---------------------------------------------------------------- 记录 / 视图 record

    record ProbeOutcome(boolean success, String failureKind, String rawMessage) {
    }

    public record VerificationResult(long credentialId, long deviceId, String channel,
                                     boolean success, String failureKind, long durationMs) {
    }

    public record VerificationRecord(long id, long credentialId, long deviceId, String channel,
                                     String result, String failureKind, Integer durationMs,
                                     String detail, String verifiedAt) {
    }

    public record CredentialOption(long credentialId, String name, String credType,
                                   String username, int tier, String lastResult,
                                   String lastVerifiedAt, int boundDevices, boolean configured) {
    }

    public record CredentialOptions(List<CredentialOption> items, int total, boolean truncated) {
    }
}
