package com.qa.autotest.application.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 判据采集入库（W57-22）：解析 junit XML 附带 JSON / pytest 标记，写
 * {@code test_result.criteria_json / criteria_verdict / criteria_missing}。
 *
 * <p>四条硬口径：
 * <ul>
 *   <li>未声明判据 → 三列 NULL（界面显示「未声明」），<b>禁止默认 PASS</b>；</li>
 *   <li>采集异常 → verdict=MISSING 且 criteria_missing 写原因，不静默吞掉；</li>
 *   <li>判据快照 JSON &gt; 64KB 截断并打标记；</li>
 *   <li>只有全部判据明确 passed=true 才判 PASS，任一缺实测值 / 未判 → MISSING。</li>
 * </ul>
 *
 * <p><b>降级</b>：junit system-out 与 pytest 标记统一走「从文本中截取 criteria JSON 对象」的
 * 解析路径，未实现独立的 {@code @pytest.mark.*} 语法解析器（见 {@link #parse}）。
 */
@Service
public class CriteriaIngestService {

    public static final int MAX_JSON_BYTES = 64 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 持久化抽象：SQL 全部参数绑定且带 project_id。 */
    public interface ResultStore {
        void updateCriteria(long projectId, String runId, long caseId,
                            String criteriaJson, String verdict, String missing);

        List<CriteriaRow> listCriteria(long projectId, String runId);
    }

    /**
     * 判据明细行：{@code verdict} 是用例级 PASS/FAIL/SKIP（展示用），
     * {@code processStatus} 是由 run 状态归一化的 completed/failed（结论矩阵用）。
     */
    public record CriteriaRow(long caseId,
                              String caseCode,
                              String verdict,
                              String processStatus,
                              String criteriaJson,
                              String criteriaVerdict,
                              String criteriaMissing) {
    }

    public record IngestRequest(long projectId, String runId, long caseId, String payload) {
    }

    public record IngestResult(String verdict, String missing, boolean declared) {
    }

    private final ResultStore store;
    private final AccessControlService access;

    public CriteriaIngestService(ResultStore store, AccessControlService access) {
        this.store = store;
        this.access = access;
    }

    /** 判据明细（GET /criteria 用），结论由 {@link CaseConclusionResolver} 统一裁定。 */
    public List<CriteriaRow> list(long projectId, String runId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return store.listCriteria(projectId, runId);
    }

    /**
     * 异步采集线程入口：run 完成回调通常不在请求线程里，没有 TenantContext，
     * 必须显式 {@code runAs} 包裹，否则租户守卫会拒绝（ADR-0006）。
     */
    public void ingestInTenant(Long userId, String username, long projectId, IngestRequest request) {
        TenantContext.runAs(userId, username, projectId, () -> ingest(request));
    }

    public IngestResult ingest(IngestRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "采集请求为空");
        }
        long projectId = request.projectId();
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.CREATE);

        String payload = request.payload();
        if (payload == null || payload.isBlank()) {
            store.updateCriteria(projectId, request.runId(), request.caseId(), null, null, null);
            return new IngestResult(null, null, false);
        }

        List<CaseCriteria> criteria;
        try {
            criteria = parse(payload);
        } catch (Exception e) {
            String reason = "采集异常：" + e.getClass().getSimpleName()
                    + " / " + abbreviate(e.getMessage());
            store.updateCriteria(projectId, request.runId(), request.caseId(),
                    null, "MISSING", reason);
            return new IngestResult("MISSING", reason, false);
        }
        if (criteria.isEmpty()) {
            store.updateCriteria(projectId, request.runId(), request.caseId(), null, null, null);
            return new IngestResult(null, null, false);
        }

        String snapshot;
        try {
            snapshot = JSON.writeValueAsString(criteria);
        } catch (Exception e) {
            String reason = "采集异常：判据快照序列化失败 / " + abbreviate(e.getMessage());
            store.updateCriteria(projectId, request.runId(), request.caseId(),
                    null, "MISSING", reason);
            return new IngestResult("MISSING", reason, false);
        }
        boolean truncated = snapshot.length() > MAX_JSON_BYTES;
        if (truncated) {
            snapshot = snapshot.substring(0, MAX_JSON_BYTES) + "[TRUNCATED]";
        }

        List<String> missingMetrics = new ArrayList<>();
        boolean allPassed = true;
        boolean anyFailed = false;
        for (CaseCriteria c : criteria) {
            if (c.actual() == null || c.actual().isBlank() || c.passed() == null) {
                missingMetrics.add(c.metric() == null ? "(未命名指标)" : c.metric());
                allPassed = false;
            } else if (!c.passed()) {
                anyFailed = true;
                allPassed = false;
            }
        }

        String verdict;
        String missing = null;
        if (!missingMetrics.isEmpty()) {
            verdict = "MISSING";
            missing = "缺失指标：" + String.join(",", missingMetrics);
        } else if (anyFailed) {
            verdict = "FAIL";
        } else if (allPassed) {
            verdict = "PASS";
        } else {
            verdict = "MISSING";
            missing = "判据存在但未给出明确达标结论";
        }
        if (truncated) {
            missing = (missing == null ? "" : missing + "; ")
                    + "判据 JSON 超过 64KB 已截断标记 [TRUNCATED]";
        }
        store.updateCriteria(projectId, request.runId(), request.caseId(),
                snapshot, verdict, missing);
        return new IngestResult(verdict, missing, true);
    }

    /**
     * 从 junit system-out / pytest 标记文本里截取第一个 JSON 对象并取 {@code criteria} 数组。
     * 找不到对象抛异常（→ MISSING）；对象里没有 criteria 返回空清单（→ 未声明 NULL）。
     */
    private static List<CaseCriteria> parse(String payload) throws Exception {
        int start = payload.indexOf('{');
        int end = payload.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("文本中未找到判据 JSON 对象");
        }
        JsonNode root = JSON.readTree(payload.substring(start, end + 1));
        JsonNode array = root.get("criteria");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<CaseCriteria> result = new ArrayList<>();
        for (JsonNode node : array) {
            result.add(new CaseCriteria(
                    text(node, "metric"), text(node, "op"), text(node, "threshold"),
                    text(node, "actual"),
                    node.hasNonNull("passed") ? node.get("passed").asBoolean() : null));
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String abbreviate(String message) {
        if (message == null || message.isBlank()) {
            return "(无详细信息)";
        }
        String trimmed = message.trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
    }
}
