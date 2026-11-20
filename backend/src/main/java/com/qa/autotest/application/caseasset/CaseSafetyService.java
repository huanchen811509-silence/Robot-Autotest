package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 安全级定级（v5.31.0 / FR-v5.31-02 / W81-04）。
 *
 * <p>为什么要有「建议」而不是直接批量定级：安全级是人工判断的结论（ADR-0013），
 * 但 1953 条 LX 里绝大多数是「没人判过」而不是「判不了」。给一个按分类的起点，
 * 让人工确认有依据；**确认动作仍然必须由人触发**，服务不做自动定级。
 */
@Service
public class CaseSafetyService {

    /** 允许写入的级别：不含 LX（不允许把已定级改回未定级）。 */
    private static final Set<String> WRITABLE = Set.of("L0", "L1", "L2");

    public record Recommendation(String categoryL1, long unrated, String suggestedLevel,
                                 String note) {
    }

    public record ApplyResult(int updated, String level, String scope) {
    }

    private final JdbcTemplate jdbc;
    private final AccessControlService access;
    private final AuditService audit;

    public CaseSafetyService(JdbcTemplate jdbc, AccessControlService access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    /** 未定级用例按一级分类聚合，并带出建议级别；未登记建议的分类回落 L1。 */
    public List<Recommendation> recommendations(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        String sql = """
                SELECT COALESCE(tc.category_l1, '') AS l1,
                       COUNT(*) AS unrated,
                       COALESCE(r.suggested_level, 'L1') AS suggested,
                       COALESCE(r.note, '未登记建议，按 L1 处理') AS note
                  FROM test_case tc
                  LEFT JOIN safety_level_recommendation r ON r.category_l1 = tc.category_l1
                 WHERE tc.project_id = ? AND tc.safety_level = 'LX'
                 GROUP BY l1, r.suggested_level, r.note
                 ORDER BY unrated DESC, l1
                """;
        return jdbc.query(sql, (rs, i) -> new Recommendation(rs.getString("l1"),
                rs.getLong("unrated"), rs.getString("suggested"), rs.getString("note")), projectId);
    }

    /**
     * 应用定级：二选一 —— 按 caseIds，或按 categoryL1。
     *
     * @param caseIds     指定用例（优先）
     * @param categoryL1  指定一级分类（caseIds 为空时生效）
     * @param level       L0 / L1 / L2
     */
    @Transactional
    public ApplyResult apply(long projectId, List<Long> caseIds, String categoryL1, String level) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        String target = level == null ? "" : level.trim().toUpperCase();
        if (!WRITABLE.contains(target)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "安全级只允许 L0/L1/L2（不可写回 LX）");
        }
        int updated;
        String scope;
        if (caseIds != null && !caseIds.isEmpty()) {
            if (caseIds.size() > 200) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "单次最多 200 条");
            }
            String in = String.join(",", caseIds.stream().map(x -> "?").toList());
            updated = jdbc.update("UPDATE test_case SET safety_level = ? WHERE project_id = ? AND id IN (" + in + ")",
                    concat(target, projectId, caseIds));
            scope = "caseIds:" + caseIds.size();
        } else if (categoryL1 != null && !categoryL1.isBlank()) {
            updated = jdbc.update("""
                    UPDATE test_case SET safety_level = ?
                     WHERE project_id = ? AND safety_level = 'LX' AND category_l1 = ?
                    """, target, projectId, categoryL1.trim());
            scope = "category:" + categoryL1.trim();
        } else {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "caseIds 与 categoryL1 至少给一个");
        }
        audit.record("case.safety.apply", "test_case", String.valueOf(projectId),
                "level=" + target + ";scope=" + scope + ";updated=" + updated);
        return new ApplyResult(updated, target, scope);
    }

    private static Object[] concat(Object first, Object second, List<Long> rest) {
        Object[] args = new Object[rest.size() + 2];
        args[0] = first;
        args[1] = second;
        for (int i = 0; i < rest.size(); i++) {
            args[i + 2] = rest.get(i);
        }
        return args;
    }
}
