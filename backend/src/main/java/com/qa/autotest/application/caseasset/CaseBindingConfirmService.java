package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 脚本绑定人工确认（v5.31.0 / FR-v5.31-01 / W81-02）。
 *
 * <p>为什么需要独立服务：绑定表在此之前只有「同步写 0」一条写路径，{@code verified}
 * 永远是 false，四态里的 {@code BOUND_VERIFIED} 在运行期不可达。本服务只做一件事——
 * 把一条已存在的绑定置为「已确认」，并留审计。
 *
 * <p>不做的事：不新建绑定、不改脚本路径、不校验收文件内容（那是同步服务的职责）。
 * 缺哈希的绑定直接拒绝确认，避免把来历不明的绑定标成可信。
 */
@Service
public class CaseBindingConfirmService {

    /** 单次批量上限，与用例批量动作一致。 */
    public static final int MAX_BATCH = 200;

    public record Failed(long caseId, String reason) {
    }

    /** 部分成功语义：失败项不回滚已成功项（与用例批量动作一致）。 */
    public record ConfirmResult(int confirmed, List<Failed> failed) {
    }

    private final CaseScriptBindingRepository bindings;
    private final TestCaseRepository cases;
    private final AccessControlService access;
    private final AuditService audit;

    public CaseBindingConfirmService(CaseScriptBindingRepository bindings, TestCaseRepository cases,
                                     AccessControlService access, AuditService audit) {
        this.bindings = bindings;
        this.cases = cases;
        this.access = access;
        this.audit = audit;
    }

    public ConfirmResult confirm(long projectId, long caseId, String kernel) {
        return confirmBatch(projectId, List.of(caseId), kernel);
    }

    /**
     * 批量确认。幂等：已确认的跳过且不计入 confirmed，不重复写审计。
     */
    public ConfirmResult confirmBatch(long projectId, List<Long> caseIds, String kernel) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        if (caseIds == null || caseIds.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "caseIds 不能为空");
        }
        if (caseIds.size() > MAX_BATCH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "单次最多确认 " + MAX_BATCH + " 条");
        }
        String k = (kernel == null || kernel.isBlank()) ? "pytest" : kernel.trim().toLowerCase();
        int confirmed = 0;
        List<Failed> failed = new ArrayList<>();
        for (Long caseId : caseIds) {
            if (caseId == null) {
                continue;
            }
            try {
                if (confirmOne(projectId, caseId, k)) {
                    confirmed++;
                }
            } catch (BusinessException ex) {
                failed.add(new Failed(caseId, ex.getMessage()));
            } catch (RuntimeException ex) {
                failed.add(new Failed(caseId,
                        ex.getMessage() == null ? "确认失败" : ex.getMessage()));
            }
        }
        return new ConfirmResult(confirmed, failed);
    }

    /** @return true = 本次确实写了库；false = 已是确认态（幂等跳过） */
    private boolean confirmOne(long projectId, long caseId, String kernel) {
        Optional<CaseScriptBinding> found = bindings.findByCase(projectId, caseId, kernel);
        if (found.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND,
                    "该用例没有 " + kernel + " 内核的脚本绑定");
        }
        CaseScriptBinding b = found.get();
        if (b.verified()) {
            return false;
        }
        if (b.scriptSha256() == null || b.scriptSha256().isBlank()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "绑定缺少脚本哈希，无法确认；请先重新同步绑定");
        }
        CaseScriptBinding updated = new CaseScriptBinding(b.id(), b.projectId(), b.caseId(),
                b.kernel(), b.scriptPath(), b.nodeId(), b.scriptSha256(), b.resolveSource(), true);
        bindings.update(updated, TenantContext.userId());
        String code = cases.find(projectId, caseId).map(TestCase::caseCode)
                .orElse("id=" + caseId);
        audit.record("case.binding.confirm", "case_script_binding", String.valueOf(b.id()),
                "case=" + code + ";kernel=" + kernel + ";path=" + b.scriptPath());
        return true;
    }
}
