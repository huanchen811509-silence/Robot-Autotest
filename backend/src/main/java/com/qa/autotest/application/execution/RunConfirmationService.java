package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * 执行计划确认摘要落库（W57-21）：把「服务端复算出的」命令清单摘要写到
 * {@code execution_plan.confirmed_digest / confirmed_at / confirmed_by}。
 *
 * <p>口径：confirmed_* 是「最近一次确认」，重复确认直接 UPDATE 覆盖（不追加版本）；
 * 审计明细走 {@link AuditService}，不在表里堆历史。确认的是摘要本身，不是「点过同意」。
 */
@Service
public class RunConfirmationService {

    /** 持久化抽象：单测用内存 fake / Mockito，不连真实 DB。 */
    public interface ExecutionPlanStore {
        boolean exists(long projectId, long planId);

        /** 覆盖式写入最近一次确认。 */
        void updateConfirmation(long projectId, long planId, String digest, Instant at, Long by);
    }

    public record Confirmation(long planId, String digest, Instant confirmedAt, Long confirmedBy) {
    }

    private final ExecutionPlanStore store;
    private final AccessControlService access;
    private final AuditService audit;

    public RunConfirmationService(ExecutionPlanStore store,
                                  AccessControlService access,
                                  AuditService audit) {
        this.store = store;
        this.access = access;
        this.audit = audit;
    }

    public Confirmation confirm(long projectId, long planId, String digest) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.CREATE);
        if (!CommandDigest.isSha256Hex(digest)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "确认摘要必须是 64 位小写 sha256 hex");
        }
        if (!store.exists(projectId, planId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "执行计划不存在：" + planId);
        }
        Instant at = Instant.now();
        Long by = TenantContext.userId();
        store.updateConfirmation(projectId, planId, digest, at, by);
        audit.record("RUN_CONFIRM", "execution_plan", Long.toString(planId),
                "digest12=" + digest.substring(0, 12) + ", scope=command-plan");
        return new Confirmation(planId, digest, at, by);
    }
}
