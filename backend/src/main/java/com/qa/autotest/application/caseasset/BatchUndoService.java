package com.qa.autotest.application.caseasset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.BatchUndoRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.ReviewSnapshot;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 批量撤销（FR-3.1-04）。
 *
 * <p>撤销窗口 5 分钟，token 一次性消费：过期、已消费、跨项目统一按冲突返回，
 * 前端不需要区分「来晚了」还是「已经撤过」。
 *
 * <p>delete 明确不可撤销（记录不存在，无法恢复），因此记录阶段就不接受该动作。
 */
@Service
public class BatchUndoService implements BatchUndoRecorder {

    public static final Duration WINDOW = Duration.ofMinutes(5);
    private static final Set<String> REVERSIBLE = Set.of(
            "submit", "approve", "reject", "setPriority", "setDomainTag", "setSafetyLevel");
    private static final String TARGET_TYPE = "test_case";

    public record UndoItem(long id, boolean ok, String message) {
    }

    public record UndoResult(String action, int restored, int failed, List<UndoItem> items) {
    }

    private final BatchUndoRepository repository;
    private final TestCaseRepository cases;
    private final AuditService audit;
    private final AccessControlService access;
    private final ObjectMapper json;
    private final Clock clock;

    public BatchUndoService(BatchUndoRepository repository, TestCaseRepository cases,
                            AuditService audit, AccessControlService access, ObjectMapper json,
                            Clock clock) {
        this.repository = repository;
        this.cases = cases;
        this.audit = audit;
        this.access = access;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public String record(long projectId, String action, List<ReviewSnapshot> snapshots) {
        if (action == null || !REVERSIBLE.contains(action) || snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        String token = java.util.UUID.randomUUID().toString();
        try {
            String payload = json.writeValueAsString(snapshots);
            repository.insert(projectId, token, action, TARGET_TYPE, payload, snapshots.size(),
                    clock.instant().plus(WINDOW), TenantContext.userId());
            return token;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "撤销快照序列化失败");
        }
    }

    public UndoResult undo(long projectId, String token) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        if (token == null || token.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "token 不能为空");
        }
        Instant now = clock.instant();
        BatchUndoRepository.UndoRecord record = repository.find(projectId, token)
                .orElseThrow(() -> conflict());
        if (record.consumedAt() != null || record.expiresAt() == null || record.expiresAt().isBefore(now)) {
            throw conflict();
        }
        if (!repository.consume(projectId, record.id(), now)) {
            throw conflict();
        }

        List<ReviewSnapshot> snapshots = deserialize(record.payload());
        List<UndoItem> items = new ArrayList<>(snapshots.size());
        int restored = 0;
        for (ReviewSnapshot snapshot : snapshots) {
            try {
                restore(projectId, record.action(), snapshot);
                audit.record("case.batch.undo." + record.action(), TARGET_TYPE,
                        String.valueOf(snapshot.id()),
                        "undo=" + token + "; restored=" + record.action());
                items.add(new UndoItem(snapshot.id(), true, null));
                restored++;
            } catch (RuntimeException e) {
                items.add(new UndoItem(snapshot.id(), false, e.getMessage()));
            }
        }
        return new UndoResult(record.action(), restored, items.size() - restored, items);
    }

    private void restore(long projectId, String action, ReviewSnapshot snapshot) {
        switch (action) {
            case "submit", "approve", "reject" -> cases.updateReview(projectId, snapshot.id(),
                    snapshot.reviewStatus(), snapshot.reviewComment(), snapshot.reviewedBy(),
                    snapshot.reviewedInstant());
            case "setPriority" -> cases.updatePriority(projectId, snapshot.id(), snapshot.priority(),
                    TenantContext.userId());
            case "setDomainTag" -> cases.updateDomainTag(projectId, snapshot.id(), snapshot.domainTag(),
                    TenantContext.userId());
            case "setSafetyLevel" -> cases.updateSafetyLevel(projectId, snapshot.id(),
                    snapshot.safetyLevel(), TenantContext.userId());
            default -> throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "该批量动作不可撤销：" + action);
        }
    }

    private List<ReviewSnapshot> deserialize(String payload) {
        try {
            List<ReviewSnapshot> snapshots = json.readValue(payload,
                    new TypeReference<List<ReviewSnapshot>>() {
                    });
            return snapshots == null ? List.of() : snapshots;
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "撤销快照读取失败");
        }
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.CONFLICT, "撤销凭据已过期、已使用或不属于当前项目");
    }
}
