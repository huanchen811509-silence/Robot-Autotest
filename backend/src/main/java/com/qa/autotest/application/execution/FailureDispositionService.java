package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.FailureDispositionRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.execution.FailureDisposition;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 失败处置闭环（FR-v5.3-06 / 验收 C16–C18 / W53-06）。
 *
 * <p>状态机：{@code open → assigned → decided → closed}，已决状态可 {@code reopen}。
 * 迁移规则集中在这里，因为它是**流程**而不是数据结构：库里的 status 只是当前值，
 * 「从哪来、能不能到这」只有这里说了算。
 *
 * <p><b>一条容易被忽略的规则</b>：已定论为 {@code known_issue} 的指纹再次出现时
 * <b>不 reopen</b>。已知问题本来就会一直失败，每出现一次就 reopen 一次，
 * 待处置列表会被同一个已知问题填满——那是把噪音重新放回队列。
 * 其余定论（产品缺陷、用例缺陷）复现必须 reopen，否则就是「改完了但没修好还说已解决」。
 *
 * <p><b>豁免不等于通过</b>：{@link #isExempt} 只回答「这条失败是否已被判定为已知问题」，
 * 用例的 verdict 仍然是 FAIL。报告里单列「已知问题」一栏，通过率分母不剔除它们，
 * 但会在旁边标出「其中 N 条为已知问题」。用处置动作刷通过率是最容易发生也最难发现的自欺。
 */
@Service
public class FailureDispositionService {

    private static final Set<String> DECISIONS = Set.of(
            FailureDisposition.DECISION_PRODUCT_BUG,
            FailureDisposition.DECISION_ENV_ISSUE,
            FailureDisposition.DECISION_CASE_DEFECT,
            FailureDisposition.DECISION_KNOWN_ISSUE,
            FailureDisposition.DECISION_WONT_FIX);

    private final FailureDispositionRepository repository;
    private final AuditService audit;

    public FailureDispositionService(FailureDispositionRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /** 归因输入：由调用方从执行结果里取，本类只负责记账与状态迁移。 */
    public record FailureContext(long caseId, String caseCode, String kernel,
                                 String category, String location, String original,
                                 String runId) {
    }

    /** 记录一次失败（幂等：同指纹同用例只累加次数）。 */
    public FailureDisposition record(long projectId, FailureContext context) {
        String kernel = normalizeKernel(context.kernel());
        String fingerprint = FailureFingerprint.of(kernel, context.category(),
                context.location(), context.original());
        Instant now = Instant.now();
        Optional<FailureDisposition> existing =
                repository.find(projectId, fingerprint, context.caseId());

        if (existing.isEmpty()) {
            FailureDisposition draft = new FailureDisposition(null, projectId, context.caseId(),
                    context.caseCode(), kernel, fingerprint, context.category(), context.location(),
                    FailureDisposition.STATUS_OPEN, null, null, null, 1, 0, null,
                    context.runId(), null, null, now, now);
            long id = repository.insert(draft, null);
            audit.record("disposition:open", "case_failure_disposition", String.valueOf(id),
                    "fingerprint=" + fingerprint + ", category=" + context.category());
            return repository.findById(projectId, id).orElse(draft);
        }

        FailureDisposition row = existing.get();
        boolean reopen = row.settled() && !row.exempted();
        FailureDisposition updated = new FailureDisposition(row.id(), row.projectId(),
                row.caseId(), context.caseCode() == null ? row.caseCode() : context.caseCode(),
                row.kernel(), row.fingerprint(), row.category(), row.location(),
                reopen ? FailureDisposition.STATUS_OPEN : row.status(),
                row.assigneeId(), row.decision(), row.note(),
                row.occurrences() + 1,
                reopen ? row.reopenCount() + 1 : row.reopenCount(),
                reopen ? "复现：" + (context.runId() == null ? "(未知 run)" : context.runId())
                        : row.reopenReason(),
                context.runId(), row.decidedAt(), row.closedAt(), row.createdAt(), now);
        repository.update(updated, null);
        if (reopen) {
            audit.recordChange("disposition:reopen", "case_failure_disposition",
                    String.valueOf(row.id()), "status=" + row.status(), "status=open");
        }
        return updated;
    }

    public FailureDisposition assign(long projectId, long id, long assigneeId) {
        FailureDisposition row = require(projectId, id);
        if (!FailureDisposition.STATUS_OPEN.equals(row.status())) {
            throw conflict(row, FailureDisposition.STATUS_OPEN);
        }
        FailureDisposition updated = copy(row, FailureDisposition.STATUS_ASSIGNED, row.decision(),
                row.note(), row.decidedAt(), row.closedAt(), row.reopenCount(),
                row.reopenReason(), assigneeId);
        repository.update(updated, null);
        audit.recordChange("disposition:assign", "case_failure_disposition", String.valueOf(id),
                "status=" + row.status(), "status=assigned, assignee=" + assigneeId);
        return updated;
    }

    /** 定论。{@code assigned} 与 {@code open} 都允许直接定论（小问题常常认领即定论）。 */
    public FailureDisposition decide(long projectId, long id, String decision, String note) {
        String normalized = normalizeDecision(decision);
        FailureDisposition row = require(projectId, id);
        if (row.settled()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该处置已定论（" + row.status() + "/" + row.decision()
                            + "）；改变结论请先 reopen 并写明原因");
        }
        FailureDisposition updated = copy(row, FailureDisposition.STATUS_DECIDED, normalized,
                note == null ? row.note() : note, Instant.now(), row.closedAt(),
                row.reopenCount(), row.reopenReason(), row.assigneeId());
        repository.update(updated, null);
        audit.recordChange("disposition:decide", "case_failure_disposition", String.valueOf(id),
                "status=" + row.status(), "status=decided, decision=" + normalized);
        return updated;
    }

    public FailureDisposition close(long projectId, long id, String note) {
        FailureDisposition row = require(projectId, id);
        if (!FailureDisposition.STATUS_DECIDED.equals(row.status())) {
            throw conflict(row, FailureDisposition.STATUS_DECIDED);
        }
        FailureDisposition updated = copy(row, FailureDisposition.STATUS_CLOSED, row.decision(),
                note == null ? row.note() : note, row.decidedAt(), Instant.now(),
                row.reopenCount(), row.reopenReason(), row.assigneeId());
        repository.update(updated, null);
        audit.recordChange("disposition:close", "case_failure_disposition", String.valueOf(id),
                "status=" + row.status(), "status=closed");
        return updated;
    }

    /** 重开：必须写原因，且计入 reopenCount（「这个问题被反复打开过几次」是核心信号）。 */
    public FailureDisposition reopen(long projectId, long id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "reopen 必须写明原因（结论被推翻时，原因比状态更重要）");
        }
        FailureDisposition row = require(projectId, id);
        if (!row.settled()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "只有已定论（decided/closed）的处置才能 reopen，当前为 " + row.status());
        }
        FailureDisposition updated = copy(row, FailureDisposition.STATUS_OPEN, row.decision(),
                row.note(), row.decidedAt(), row.closedAt(), row.reopenCount() + 1,
                reason.trim(), row.assigneeId());
        repository.update(updated, null);
        audit.recordChange("disposition:reopen", "case_failure_disposition", String.valueOf(id),
                "status=" + row.status(), "status=open, reason=" + reason.trim());
        return updated;
    }

    /**
     * 该失败是否已被判定为已知问题（用于报告里单列，不用于改 verdict）。
     *
     * <p>同时看「按用例」与「按指纹批量（caseId=0）」两行：批量处置的意义就是覆盖同类，
     * 只看用例行会让批量处置完全失效。
     */
    public boolean isExempt(long projectId, long caseId, String fingerprint) {
        Optional<FailureDisposition> byCase = repository.find(projectId, fingerprint, caseId);
        if (byCase.map(FailureDisposition::exempted).orElse(false)) {
            return true;
        }
        return repository.find(projectId, fingerprint, 0L)
                .map(FailureDisposition::exempted).orElse(false);
    }

    public List<FailureDisposition> list(long projectId, String status) {
        return repository.list(projectId, status);
    }

    public List<FailureDisposition> listByCase(long projectId, long caseId) {
        return repository.listByCase(projectId, caseId);
    }

    /** 处置台账：各状态计数 + 已知问题数，给页面顶部。 */
    public record Ledger(long open, long assigned, long decided, long closed, long exempted) {
    }

    public Ledger ledger(long projectId) {
        return new Ledger(
                repository.countByStatus(projectId, FailureDisposition.STATUS_OPEN),
                repository.countByStatus(projectId, FailureDisposition.STATUS_ASSIGNED),
                repository.countByStatus(projectId, FailureDisposition.STATUS_DECIDED),
                repository.countByStatus(projectId, FailureDisposition.STATUS_CLOSED),
                repository.listExempted(projectId).size());
    }

    // ------------------------------------------------------------------ 内部

    private FailureDisposition require(long projectId, long id) {
        return repository.findById(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "处置记录不存在：" + id));
    }

    private static BusinessException conflict(FailureDisposition row, String expected) {
        return new BusinessException(ErrorCode.CONFLICT,
                "状态不允许该操作：当前 " + row.status() + "，要求 " + expected);
    }

    private static FailureDisposition copy(FailureDisposition row, String status, String decision,
                                           String note, Instant decidedAt, Instant closedAt,
                                           int reopenCount, String reopenReason,
                                           Long assigneeId) {
        return new FailureDisposition(row.id(), row.projectId(), row.caseId(), row.caseCode(),
                row.kernel(), row.fingerprint(), row.category(), row.location(), status,
                assigneeId, decision, note, row.occurrences(), reopenCount, reopenReason,
                row.lastRunId(), decidedAt, closedAt, row.createdAt(), Instant.now());
    }

    private static String normalizeDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "结论不能为空，可选：" + String.join(" / ", DECISIONS));
        }
        String value = decision.trim().toLowerCase(Locale.ROOT);
        if (!DECISIONS.contains(value)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "未知结论 " + decision + "，可选：" + String.join(" / ", DECISIONS));
        }
        return value;
    }

    private static String normalizeKernel(String kernel) {
        return kernel == null || kernel.isBlank() ? "robot"
                : kernel.trim().toLowerCase(Locale.ROOT);
    }
}
