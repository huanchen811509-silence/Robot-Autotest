package com.qa.autotest.application.ai;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.AiSessionRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.ai.AiActionResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * AI 面板编排（AIX-04 / AIX-05 / FR-AI-01..08）：建议 / 发起执行（二次确认）/ 对话 /
 * 效果评估，全部动作写审计并落三张 AI 表；无 CLI 时显式 DEGRADED 而不阻断主功能。
 *
 * <p>四参构造保留给 AiPanelTest.java:32 的骨架替身：无数据库、无闸门、无子进程；
 * 生产走六参构造（Spring 注入 AiFeatureGate / AiSessionRepository）。
 */
@Service
public class AiPanelService {

    private final AiCliProbe probe;
    private final ContextPacker packer;
    private final AiProcessManager processes;
    private final AuditService audit;
    private final AiFeatureGate gate;
    private final AiSessionRepository repository;
    private final Map<Long, Boolean> openSessions = new ConcurrentHashMap<>();
    private final Map<Long, ProcessHandle> sessionHandles = new ConcurrentHashMap<>();

    @Autowired
    public AiPanelService(AiCliProbe probe, ContextPacker packer, AiProcessManager processes,
                           AuditService audit, AiFeatureGate gate, AiSessionRepository repository) {
        this.probe = probe;
        this.packer = packer;
        this.processes = processes;
        this.audit = audit;
        this.gate = gate;
        this.repository = repository;
    }

    /** 兼容 AiPanelTest.java:32 的四参构造：不接闸门 / 数据库 / 子进程。 */
    public AiPanelService(AiCliProbe probe, ContextPacker packer, AiProcessManager processes,
                           AuditService audit) {
        this(probe, packer, processes, audit, AiFeatureGate.permissive(), null);
    }

    /** FR-AI-07：ai.enabled=false 时所有动作明确禁用。state 只报告不抛，供前端隐藏入口。 */
    public void requireEnabled() {
        if (!gate.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "AI 助手已禁用（ai.enabled=false）");
        }
    }

    public Map<String, Object> state(long projectId) {
        Map<String, Object> result = new LinkedHashMap<>(probe.probe());
        boolean enabled = gate.enabled();
        result.put("enabled", enabled);
        result.put("realCliEnabled", AiFeatureGate.realCliFeatureEnabled());
        result.put("projectId", projectId);
        result.put("timeoutSeconds", processes.timeoutSeconds());
        result.put("encoding", processes.encoding());
        result.put("contextMaxChars", ContextPacker.DEFAULT_MAX_CHARS);
        result.put("executeRequiresConfirmation", true);
        if (!enabled) {
            result.put("mode", "DISABLED");
        }
        if (repository != null) {
            for (String cli : AiCliAdapter.CANDIDATES) {
                repository.recordProbe(cli, Boolean.TRUE.equals(result.get(cli)), true);
            }
        }
        return result;
    }

    /** FR-AI-03：会话 id 来自 ai_session 自增主键；无库时用 UUID 派生全局唯一 id。 */
    public long open(long projectId, long userId) {
        requireEnabled();
        String uuid = UUID.randomUUID().toString();
        long id = repository == null ? fallbackId(uuid)
                : repository.openSession(projectId, userId, uuid);
        openSessions.put(id, true);
        audit.record("AI_SESSION_OPEN", "ai_session", String.valueOf(id),
                "project=" + projectId + "; user=" + userId + "; uuid=" + uuid);
        return id;
    }

    private static long fallbackId(String uuid) {
        return UUID.fromString(uuid).getMostSignificantBits() & Long.MAX_VALUE;
    }

    /** FR-AI-01 / FR-AI-06：L2 建议接真子进程，stdout 进返回体；无 CLI 时显式 DEGRADED。 */
    public AiActionResult suggest(long sessionId, Map<String, String> context) {
        requireEnabled();
        String packed = packer.pack(context, ContextPacker.DEFAULT_MAX_CHARS);
        String prompt = "你是测试平台 AI 助手，请基于以下脱敏上下文给出建议：\n" + packed;
        AiProcessResult pr = invoke(sessionId, prompt);
        boolean degraded = !probe.available() || !pr.executed() || pr.stdout().isBlank();
        String level;
        String summary;
        if (degraded) {
            level = "L1";
            summary = "CLI 不可用，已显式降级为 L1 脱敏上下文摘要（" + packed.length()
                    + " 字符）；" + pr.reason();
        } else {
            level = "L2";
            summary = "L2 建议（模型回显）: " + firstLine(pr.stdout(), 240)
                    + " | 上下文 " + packed.length() + " 字符";
        }
        AiActionResult result = new AiActionResult("SUGGEST", level, degraded, summary, true,
                pr.stdout(), pr.stderr(), pr.exitCode(), pr.durationMs(), pr.cli(), pr.timedOut());
        recordAction(sessionId, result, false);
        audit.record("AI_SUGGEST", "ai_action", String.valueOf(sessionId), summary);
        return result;
    }

    /** L3 发起执行：二次确认是硬闸门，缺确认一律 403；stdout 一并返回。 */
    public AiActionResult execute(long sessionId, String target, boolean confirmed) {
        requireEnabled();
        if (!Boolean.TRUE.equals(openSessions.get(sessionId))) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在或已关闭");
        }
        if (!confirmed) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "发起执行必须二次确认");
        }
        if (!probe.available()) {
            AiActionResult degraded = new AiActionResult("EXECUTE", "L3", true,
                    "CLI 不可用，动作显式降级为仅记录，不发起执行", true,
                    "", "", -1, 0L, null, false);
            recordAction(sessionId, degraded, true);
            audit.record("AI_EXECUTE_DEGRADED", "ai_action",
                    String.valueOf(sessionId), degraded.summary());
            return degraded;
        }
        String prompt = "二次确认已通过，请对目标[" + (target == null ? "" : target)
                + "]给出执行说明（L3，仅解释不落地修改）：";
        AiProcessResult pr = invoke(sessionId, prompt);
        String summary = "已按二次确认发起执行: " + (target == null ? "" : target);
        if (pr.executed() && !pr.stdout().isBlank()) {
            summary += " | 模型回显: " + firstLine(pr.stdout(), 240);
        }
        AiActionResult result = new AiActionResult("EXECUTE", "L3", false, summary, true,
                pr.stdout(), pr.stderr(), pr.exitCode(), pr.durationMs(), pr.cli(), pr.timedOut());
        recordAction(sessionId, result, true);
        audit.record("AI_EXECUTE", "ai_action", String.valueOf(sessionId), summary);
        return result;
    }

    public AiActionResult evaluate(long sessionId, int improved, int regressed) {
        requireEnabled();
        String summary = "效果评估 improved=" + improved + "; regressed=" + regressed;
        AiActionResult result = new AiActionResult("EVALUATE", "L1", false, summary, true);
        recordAction(sessionId, result, false);
        audit.record("AI_EVALUATE", "ai_action", String.valueOf(sessionId), summary);
        return result;
    }

    /** FR-AI-08：controller 从这里取真实进程句柄传给 terminate，不再传 null。 */
    public ProcessHandle handleFor(long sessionId) {
        return sessionHandles.get(sessionId);
    }

    /** 一键终止：关闭会话、终止进程树、写审计；与 AI 是否可用无关。 */
    public AiActionResult terminate(long sessionId, ProcessHandle process) {
        requireEnabled();
        ProcessHandle handle = process != null ? process : sessionHandles.remove(sessionId);
        openSessions.remove(sessionId);
        sessionHandles.remove(sessionId);
        processes.terminate(handle);
        if (repository != null) {
            repository.closeSession(sessionId);
        }
        String summary = "会话已关闭，进程树已终止";
        AiActionResult result = new AiActionResult("TERMINATE", "L1", false, summary, true);
        recordAction(sessionId, result, false);
        audit.record("AI_TERMINATE", "ai_action", String.valueOf(sessionId), summary);
        return result;
    }

    /** FR-AI-05：多轮对话；前端把累积会话作为 prompt 传入，服务侧统一经 ContextPacker 脱敏。 */
    public Map<String, Object> chat(long projectId, long sessionId, String prompt) {
        requireEnabled();
        long sid = sessionId > 0 ? sessionId : open(projectId, 0L);
        Map<String, String> context = new LinkedHashMap<>();
        context.put("conversation", prompt == null ? "" : prompt);
        String packed = packer.pack(context, ContextPacker.DEFAULT_MAX_CHARS);
        AiProcessResult pr = invoke(sid, "请回答用户问题：\n" + packed);
        boolean degraded = !probe.available() || !pr.executed() || pr.stdout().isBlank();
        String answer = pr.stdout().isBlank()
                ? "CLI 不可用，已显式降级（" + pr.reason() + "）"
                : pr.stdout();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", sid);
        out.put("answer", answer);
        out.put("stdout", pr.stdout());
        out.put("stderr", pr.stderr());
        out.put("exitCode", pr.exitCode());
        out.put("durationMs", pr.durationMs());
        out.put("degraded", degraded);
        out.put("mode", degraded ? "DEGRADED" : "NORMAL");
        out.put("cli", pr.cli());
        out.put("timedOut", pr.timedOut());
        out.put("audited", true);
        recordAction(sid, new AiActionResult("CHAT", degraded ? "L1" : "L2", degraded,
                firstLine(answer, 240), true), false);
        audit.record("AI_CHAT", "ai_action", String.valueOf(sid),
                "mode=" + out.get("mode") + "; cli=" + pr.cli());
        return out;
    }

    private AiProcessResult invoke(long sessionId, String prompt) {
        if (!probe.available()) {
            return AiProcessResult.notExecuted(probe.primaryCli(), "CLI 不可用，已降级");
        }
        AiProcessResult result = processes.execute(probe.primaryCli(), prompt);
        if (result.handle() != null) {
            sessionHandles.put(sessionId, result.handle());
        }
        return result;
    }

    private void recordAction(long sessionId, AiActionResult result, boolean confirmed) {
        if (repository == null) {
            return;
        }
        repository.recordAction(sessionId, result.action(), result.level(), confirmed,
                result.degraded() ? "DEGRADED" : "OK", result.summary(),
                result.stdout(), result.stderr(), result.exitCode(), result.durationMs(),
                result.cli(), result.timedOut());
    }

    private static String firstLine(String value, int max) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String line = value.strip().split("\\R", 2)[0];
        return line.length() <= max ? line : line.substring(0, max);
    }
}
