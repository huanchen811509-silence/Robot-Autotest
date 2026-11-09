package com.qa.autotest.application.archive;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ArchiveRepository;
import com.qa.autotest.application.repository.AuditRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.archive.ArchivePolicy;
import com.qa.autotest.domain.archive.ArchivedRunInfo;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 归档与保留（FR-3.4-01 / EW34-01）。
 *
 * <p>边界：
 * <ul>
 *   <li>只搬 {@code test_result} / {@code keyword_result} 明细，{@code run} 主记录与聚合数字留在热表；</li>
 *   <li>冷表保留原主键，恢复按原 id 插回，引用关系不错位；原 id 被占用时整事务失败并显式报错；</li>
 *   <li>dry-run 默认 true：先看清单再执行，执行还必须先启用策略；</li>
 *   <li>每个运行一次事务：归档索引 + 明细搬迁 + 审计同时成立或同时回滚。</li>
 * </ul>
 */
@Service
public class ArchiveService {

    private static final Logger log = LoggerFactory.getLogger(ArchiveService.class);

    static final String AUDIT_ARCHIVE = "archive_run";
    static final String AUDIT_RESTORE = "archive_restore";
    static final String AUDIT_POLICY = "archive_policy";

    public record PreviewItem(String runId, int testResults, int keywordResults, long ageDays) {
    }

    public record Preview(boolean dryRun, int retentionDays, int batchSize, List<PreviewItem> items) {
    }

    public record ExecuteResult(boolean dryRun, int archivedRuns, int movedTestResults,
                                int movedKeywordResults, List<String> skipped) {
    }

    public record RestoreResult(String runId, int restoredTestResults, int restoredKeywordResults) {
    }

    private final ArchiveRepository repo;
    private final AuditRepository audit;
    private final AccessControlService access;
    private final Clock clock;
    private final TransactionTemplate tx;

    @Autowired
    public ArchiveService(ArchiveRepository repo, AuditRepository audit,
                          AccessControlService access, Clock executionClock,
                          PlatformTransactionManager txManager) {
        this(repo, audit, access, executionClock, new TransactionTemplate(txManager));
    }

    /** 测试装配：{@code tx=null} 表示不分事务。 */
    public ArchiveService(ArchiveRepository repo, AuditRepository audit,
                          AccessControlService access, Clock executionClock, TransactionTemplate tx) {
        this.repo = repo;
        this.audit = audit;
        this.access = access;
        this.clock = executionClock;
        this.tx = tx;
    }

    public ArchivePolicy policy(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return repo.policy(projectId);
    }

    public ArchivePolicy savePolicy(long projectId, boolean enabled, int retentionDays,
                                    int batchSize) {
        requireAdmin(projectId);
        ArchivePolicy policy = new ArchivePolicy(projectId, enabled, retentionDays, batchSize);
        repo.savePolicy(projectId, policy, TenantContext.userId());
        audit.insert(projectId, TenantContext.userId(), AUDIT_POLICY, "archive_policy",
                String.valueOf(projectId), "{\"enabled\":" + enabled
                        + ",\"retentionDays\":" + policy.retentionDays()
                        + ",\"batchSize\":" + policy.batchSize() + "}");
        return policy;
    }

    /** 候选清单：只读，不搬任何行。 */
    public Preview preview(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        ArchivePolicy policy = repo.policy(projectId);
        List<ArchiveRepository.RunCandidate> candidates = repo.candidates(projectId,
                clock.instant().minus(Duration.ofDays(policy.retentionDays())), policy.batchSize());
        List<PreviewItem> items = new ArrayList<>(candidates.size());
        for (ArchiveRepository.RunCandidate candidate : candidates) {
            ArchiveRepository.Counts counts = repo.counts(candidate.id(), projectId);
            items.add(new PreviewItem(candidate.runId(), counts.testResults(),
                    counts.keywordResults(), ageDays(candidate.createdAt())));
        }
        return new Preview(true, policy.retentionDays(), policy.batchSize(), items);
    }

    /**
     * 执行归档；{@code dryRun=true} 只返回清单。
     *
     * @throws BusinessException 策略未启用时拒绝执行（默认不动数据）
     */
    public ExecuteResult run(long projectId, boolean dryRun) {
        requireAdmin(projectId);
        ArchivePolicy policy = repo.policy(projectId);
        if (dryRun) {
            Preview preview = preview(projectId);
            int tests = preview.items().stream().mapToInt(PreviewItem::testResults).sum();
            int keywords = preview.items().stream().mapToInt(PreviewItem::keywordResults).sum();
            return new ExecuteResult(true, preview.items().size(), tests, keywords, List.of());
        }
        if (!policy.enabled()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "归档策略未启用：先保存 enabled=true 的策略再执行");
        }
        List<ArchiveRepository.RunCandidate> candidates = repo.candidates(projectId,
                clock.instant().minus(Duration.ofDays(policy.retentionDays())), policy.batchSize());
        int archived = 0;
        int movedTests = 0;
        int movedKeywords = 0;
        List<String> skipped = new ArrayList<>();
        for (ArchiveRepository.RunCandidate candidate : candidates) {
            try {
                ArchiveRepository.Counts counts = archiveOne(projectId, candidate);
                archived++;
                movedTests += counts.testResults();
                movedKeywords += counts.keywordResults();
            } catch (RuntimeException failure) {
                skipped.add(candidate.runId() + ": " + failure.getMessage());
                log.warn("归档运行失败 projectId={} runId={}: {}", projectId, candidate.runId(),
                        failure.getMessage());
            }
        }
        log.info("归档完成 projectId={} runs={} tests={} keywords={} skipped={}",
                projectId, archived, movedTests, movedKeywords, skipped.size());
        return new ExecuteResult(false, archived, movedTests, movedKeywords, skipped);
    }

    private ArchiveRepository.Counts archiveOne(long projectId,
                                                ArchiveRepository.RunCandidate candidate) {
        ArchiveRepository.Counts counts = repo.counts(candidate.id(), projectId);
        Runnable work = () -> {
            long archiveId = repo.insertArchive(projectId, candidate.id(), candidate.runId(),
                    counts.testResults(), counts.keywordResults());
            repo.moveKeywordResults(candidate.id(), archiveId, projectId);
            repo.moveTestResults(candidate.id(), archiveId, projectId);
            audit.insert(projectId, TenantContext.userId(), AUDIT_ARCHIVE, "run",
                    candidate.runId(), "{\"testResults\":" + counts.testResults()
                            + ",\"keywordResults\":" + counts.keywordResults() + "}");
        };
        if (tx == null) {
            work.run();
        } else {
            tx.executeWithoutResult(status -> work.run());
        }
        return counts;
    }

    /** 恢复：按原主键搬回热表；冲突时整事务失败，不静默丢数据。 */
    public RestoreResult restore(long projectId, String runId) {
        requireAdmin(projectId);
        ArchivedRunInfo info = repo.findArchive(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "未找到归档记录"));
        if (info.restored()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "该运行已恢复，无需重复操作");
        }
        int[] counts = new int[2];
        Runnable work = () -> {
            counts[0] = repo.restoreTestResults(info.id(), projectId);
            counts[1] = repo.restoreKeywordResults(info.id(), projectId);
            repo.markRestored(projectId, runId);
            repo.deleteArchiveDetails(info.id());
            audit.insert(projectId, TenantContext.userId(), AUDIT_RESTORE, "run", runId,
                    "{\"testResults\":" + counts[0] + ",\"keywordResults\":" + counts[1] + "}");
        };
        try {
            if (tx == null) {
                work.run();
            } else {
                tx.executeWithoutResult(status -> work.run());
            }
        } catch (org.springframework.dao.DuplicateKeyException conflict) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "恢复失败：原主键已被新数据占用（" + conflict.getMostSpecificCause().getMessage() + "）");
        }
        log.info("归档恢复完成 projectId={} runId={} tests={} keywords={}",
                projectId, runId, counts[0], counts[1]);
        return new RestoreResult(runId, counts[0], counts[1]);
    }

    public List<ArchivedRunInfo> archives(long projectId, int limit) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return repo.archives(projectId, limit);
    }

    /** 冷表查询：归档后无需恢复即可查看明细。 */
    public List<ArchiveRepository.ColdResult> coldResults(long projectId, String runId, int limit) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        Optional<ArchivedRunInfo> info = repo.findArchive(projectId, runId);
        if (info.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到归档记录");
        }
        return repo.coldResults(info.get().id(), projectId, limit);
    }

    /** 定时入口：只处理策略启用的项目，身份记为 system。 */
    public void executeAllProjects() {
        for (Long projectId : repo.enabledProjects()) {
            TenantContext.runAs(null, "system", projectId, () -> {
                try {
                    run(projectId, false);
                } catch (RuntimeException ex) {
                    log.warn("定时归档项目失败 projectId={}: {}", projectId, ex.getMessage());
                }
            });
        }
    }

    private void requireAdmin(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有管理员可以管理归档");
        }
    }

    private long ageDays(Instant createdAt) {
        if (createdAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(createdAt, clock.instant()).toDays());
    }
}
