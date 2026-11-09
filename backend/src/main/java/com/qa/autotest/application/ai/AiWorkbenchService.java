package com.qa.autotest.application.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.AiPromptRepository;
import com.qa.autotest.application.repository.AiWorkbenchRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.ai.AiAnswer;
import com.qa.autotest.domain.ai.DraftCase;
import com.qa.autotest.domain.ai.PromptTemplate;
import com.qa.autotest.domain.caseasset.TestCase;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * AI 工作台编排（FR-3.3-01..08）。
 *
 * <p>所有动作先过项目权限、平台开关、项目开关与日配额；回答统一做事实一致性校验并落会话，
 * 保证「重登能恢复、导出可追溯、配额可解释」。用例草稿只在显式 {@code reviewed=true} 时入库。
 */
@Service
public class AiWorkbenchService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 一次动作的结果：会话、回答、校验报告与耗时。 */
    public record AiResult(long sessionId, AiAnswer answer, AiFactVerifier.Report verification,
                           long elapsedMs) {
    }

    /** 配额视图：已用 / 上限 / 重置时间。 */
    public record QuotaView(int dailyQuota, long used, long remaining, String resetAt) {
    }

    private final AiPanelService panel;
    private final AiEvidenceService evidence;
    private final AiAnswerFactory factory;
    private final AiFactVerifier verifier;
    private final AiWorkbenchRepository workbench;
    private final AiPromptRepository prompts;
    private final TestCaseRepository cases;
    private final AccessControlService access;
    private final ObjectMapper mapper;

    public AiWorkbenchService(AiPanelService panel, AiEvidenceService evidence,
                              AiAnswerFactory factory, AiFactVerifier verifier,
                              AiWorkbenchRepository workbench, AiPromptRepository prompts,
                              TestCaseRepository cases, AccessControlService access,
                              ObjectMapper mapper) {
        this.panel = panel;
        this.evidence = evidence;
        this.factory = factory;
        this.verifier = verifier;
        this.workbench = workbench;
        this.prompts = prompts;
        this.cases = cases;
        this.access = access;
        this.mapper = mapper;
    }

    public AiResult triage(long projectId, String runId, long sessionId, Long userId) {
        requireQuota(projectId);
        long started = System.currentTimeMillis();
        long session = ensureSession(projectId, sessionId, userId);
        AiEvidenceService.RunEvidence ctx = evidence.forRun(projectId, runId);
        AiAnswer answer = factory.triage(runId, ctx);
        persist(projectId, session, "请分诊运行 " + runId, answer);
        return result(session, answer, ctx, started);
    }

    public AiResult narrative(long projectId, String runId, long sessionId, Long userId) {
        requireQuota(projectId);
        long started = System.currentTimeMillis();
        long session = ensureSession(projectId, sessionId, userId);
        AiEvidenceService.RunEvidence ctx = evidence.forRun(projectId, runId);
        AiAnswer answer = factory.narrative(ctx);
        persist(projectId, session, "请生成运行 " + runId + " 的报告叙述", answer);
        return result(session, answer, ctx, started);
    }

    public AiResult chat(long projectId, long sessionId, String prompt, Long userId) {
        requireQuota(projectId);
        long started = System.currentTimeMillis();
        long session = ensureSession(projectId, sessionId, userId);
        AiEvidenceService.RunEvidence ctx = evidence.forPrompt(projectId, prompt);
        AiAnswer answer = factory.chat(ctx, prompt);
        persist(projectId, session, prompt, answer);
        AiFactVerifier.Report report = verifier.verify(answer,
                ctx == null ? com.qa.autotest.domain.ai.AiEvidence.empty("chat")
                        : ctx.evidence());
        return new AiResult(session, answer, report, System.currentTimeMillis() - started);
    }

    public AiResult caseDraft(long projectId, String requirement, String module, int count,
                              long sessionId, Long userId) {
        requireQuota(projectId);
        long started = System.currentTimeMillis();
        long session = ensureSession(projectId, sessionId, userId);
        var evidenceSet = evidence.forRequirement(projectId, requirement);
        AiAnswer answer = factory.caseDraft(evidenceSet, module, count);
        persist(projectId, session, "请起草用例：" + module, answer);
        return new AiResult(session, answer,
                verifier.verify(answer, evidenceSet),
                System.currentTimeMillis() - started);
    }

    /** 结构化草稿列表，供前端逐条编辑后再走「审阅后保存」。 */
    public List<DraftCase> draftCases(long projectId, String module, int count) {
        access.requireProjectAccess(projectId);
        return factory.draftCases(module, count);
    }

    /**
     * 审阅后保存草稿（FR-3.3-06 的闸门）。
     *
     * <p>{@code reviewed=false} 一律拒绝；保存的用例带 {@code AI_DRAFT} 来源与 {@code PENDING}
     * 评审态，不会直接进入可执行状态。
     */
    public List<Long> applyDrafts(long projectId, List<DraftCase> drafts, boolean reviewed,
                                  Long userId) {
        if (!reviewed) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "必须逐条人工审阅后才能保存草稿");
        }
        if (drafts == null || drafts.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "没有可保存的草稿");
        }
        access.requireProjectAccess(projectId);
        List<Long> created = new ArrayList<>();
        String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .format(java.time.LocalDateTime.now());
        for (int i = 0; i < drafts.size(); i++) {
            DraftCase draft = drafts.get(i);
            if (draft.title() == null || draft.title().isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "草稿标题不能为空");
            }
            String code = "AI-" + stamp + "-" + (i + 1);
            TestCase testCase = new TestCase(null, projectId, code, draft.title(), "功能",
                    "P2", draft.precondition(), draft.expected(), null, "PENDING",
                    "AI_DRAFT", null, "步骤：" + draft.steps(), "LX");
            created.add(cases.insert(testCase, userId));
        }
        return List.copyOf(created);
    }

    public List<AiWorkbenchRepository.SessionRow> sessions(long projectId, Long userId,
                                                            int limit) {
        access.requireProjectAccess(projectId);
        return workbench.sessions(projectId, userId == null ? 0L : userId, limit);
    }

    public List<AiWorkbenchRepository.MessageRow> messages(long projectId, long sessionId) {
        access.requireProjectAccess(projectId);
        if (!workbench.sessionBelongs(projectId, sessionId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
        }
        return workbench.messages(projectId, sessionId);
    }

    public String export(long projectId, long sessionId) {
        List<AiWorkbenchRepository.MessageRow> rows = messages(projectId, sessionId);
        StringBuilder sb = new StringBuilder("# AI 会话导出\n\n");
        for (AiWorkbenchRepository.MessageRow row : rows) {
            sb.append(row.role().equals("user") ? "## 我\n\n" : "## 助手\n\n");
            sb.append(row.content()).append("\n\n");
            if (row.citationsJson() != null && !row.citationsJson().isBlank()) {
                sb.append("证据：").append(row.citationsJson()).append("\n\n");
            }
        }
        return sb.toString();
    }

    public AiWorkbenchRepository.Config config(long projectId) {
        access.requireProjectAccess(projectId);
        return workbench.config(projectId);
    }

    public AiWorkbenchRepository.Config saveConfig(long projectId, AiWorkbenchRepository.Config cfg,
                                                   Long actorId) {
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员可以修改 AI 项目配置");
        }
        if (cfg.maxContextChars() < 200 || cfg.maxContextChars() > 20000) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "上下文上限应在 200–20000 字符之间");
        }
        if (cfg.dailyQuota() < 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "日配额不能为负数");
        }
        return workbench.saveConfig(projectId, cfg, actorId);
    }

    public QuotaView quota(long projectId) {
        access.requireProjectAccess(projectId);
        AiWorkbenchRepository.Config cfg = workbench.config(projectId);
        long used = workbench.countActionsSince(projectId, dayStart());
        long remaining = cfg.dailyQuota() <= 0 ? -1
                : Math.max(0, cfg.dailyQuota() - used);
        String reset = LocalDate.now(ZONE).plusDays(1).atStartOfDay(ZONE).toInstant().toString();
        return new QuotaView(cfg.dailyQuota(), used, remaining, reset);
    }

    public List<PromptTemplate> templates(long projectId) {
        access.requireProjectAccess(projectId);
        var merged = new java.util.LinkedHashMap<String, PromptTemplate>();
        for (PromptTemplate template : prompts.list(projectId)) {
            merged.put(template.key(), template);
        }
        return List.copyOf(merged.values());
    }

    public PromptTemplate saveTemplate(long projectId, String key, String name, String body,
                                       Long actorId) {
        access.requireProjectAccess(projectId);
        if (key == null || !key.matches("[a-z0-9_]{2,64}")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "模板键只能是小写字母、数字与下划线，长度 2–64");
        }
        if (name == null || name.isBlank() || body == null || body.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "模板名称与正文不能为空");
        }
        return prompts.save(projectId, key, name.strip(), body.strip(), actorId);
    }

    public boolean deleteTemplate(long projectId, String key) {
        access.requireProjectAccess(projectId);
        return prompts.delete(projectId, key);
    }

    private AiResult result(long session, AiAnswer answer, AiEvidenceService.RunEvidence ctx,
                            long started) {
        return new AiResult(session, answer, verifier.verify(answer, ctx.evidence()),
                System.currentTimeMillis() - started);
    }

    private long ensureSession(long projectId, long sessionId, Long userId) {
        access.requireProjectAccess(projectId);
        if (sessionId > 0) {
            if (!workbench.sessionBelongs(projectId, sessionId)) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在");
            }
            return sessionId;
        }
        return panel.open(projectId, userId == null ? 0L : userId);
    }

    /** 流式入口的同步前置：配额与会话先过，失败直接返回 HTTP 错误，不建立空流。 */
    public long prepareStream(long projectId, long sessionId, Long userId) {
        requireQuota(projectId);
        return ensureSession(projectId, sessionId, userId);
    }

    /** 流式回答内容（与同步对话同一装配器，保证两种模式结论一致）。 */
    public AiAnswer buildChatAnswer(long projectId, String prompt) {
        return factory.chat(evidence.forPrompt(projectId, prompt), prompt);
    }

    public AiFactVerifier.Report verifyChat(long projectId, String prompt, AiAnswer answer) {
        AiEvidenceService.RunEvidence ctx = evidence.forPrompt(projectId, prompt);
        return verifier.verify(answer,
                ctx == null ? com.qa.autotest.domain.ai.AiEvidence.empty("chat") : ctx.evidence());
    }

    /** 流式结束（完成/取消/超时）统一落库，mode 记录真实结束原因。 */
    public void recordStream(long projectId, long sessionId, String prompt, AiAnswer answer,
                             String mode) {
        persist(projectId, sessionId, prompt, answer, mode);
    }

    private void persist(long projectId, long session, String userPrompt, AiAnswer answer) {
        persist(projectId, session, userPrompt, answer, answer.mode());
    }

    private void persist(long projectId, long session, String userPrompt, AiAnswer answer,
                         String mode) {
        if (userPrompt != null && !userPrompt.isBlank()) {
            append(projectId, session, "user", userPrompt, null, null);
        }
        append(projectId, session, "ai", answer.withCitations(),
                json(answer.citations()), mode);
    }

    private void append(long projectId, long session, String role, String content,
                        String citationsJson, String mode) {
        workbench.appendMessage(projectId, session, role, content, citationsJson, mode,
                org.slf4j.MDC.get("traceId"));
    }

    private String json(Object value) {
        try {
            return value == null ? null : mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void requireQuota(long projectId) {
        access.requireProjectAccess(projectId);
        AiWorkbenchRepository.Config cfg = workbench.config(projectId);
        if (!cfg.enabled()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "项目已关闭 AI");
        }
        long used = workbench.countActionsSince(projectId, dayStart());
        if (cfg.dailyQuota() > 0 && used >= cfg.dailyQuota()) {
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS,
                    "AI 日配额已用尽（" + used + "/" + cfg.dailyQuota() + "），次日 0 点重置");
        }
    }

    private static java.time.Instant dayStart() {
        return LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
    }
}
