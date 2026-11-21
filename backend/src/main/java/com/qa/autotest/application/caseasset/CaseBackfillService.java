package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公共库 → 项目用例的内容回填（FR-UIE-07，2026-11-21 大师拍板「一次性快照」）。
 *
 * <p><b>为什么需要它</b>：pytest 工程的项目用例是从台账 sheet 导入的，那张表只有
 * 编号/名称/分类/优先级；预置条件、测试步骤、预期结果一直为空。公共库里 414 条同编号
 * 用例这些字段是齐的，但两库物理隔离、{@code case_code} 不是外键——没有任何机制会把
 * 内容带过来。于是「打开用例只有四个『源表无值』」。
 *
 * <p><b>为什么是快照而不是实时引用</b>：实时引用会让公共库改一条、所有项目跟着变，
 * 项目侧无法差异化，且公共库停用时项目详情会突然变空。快照把内容复制进项目，
 * 之后各改各的，来源记在 {@code test_case.inherited_from} 上。
 *
 * <p><b>不覆盖本地修改</b>：只填「空字段」；已有值的字段一律跳过。整条用例三个字段
 * 都有值且已有步骤时记 {@code ALREADY_FILLED}，计为跳过。
 */
@Service
public class CaseBackfillService {

    private final JdbcTemplate jdbc;
    private final ProjectRepository projects;
    private final AccessControlService access;

    public CaseBackfillService(JdbcTemplate jdbc, ProjectRepository projects,
                               AccessControlService access) {
        this.jdbc = jdbc;
        this.projects = projects;
        this.access = access;
    }

    /** 一条用例的回填判定。 */
    private record Row(long id, String caseCode, String precondition, String expected,
                       String caseKind, int steps) {
    }

    /** 预演结果：不写库。{@code samples} 给出前 10 条将被填充的编号，便于人工核对。 */
    public record Preview(long sourceProjectId, long targetProjectId, int scanned, int fill,
                          int skip, Map<String, Integer> reasons, List<String> samples) {
    }

    /** 执行结果。 */
    public record Applied(long batchId, long sourceProjectId, long targetProjectId, int scanned,
                          int fill, int skip, Map<String, Integer> reasons) {
    }

    /** 内容来源固定为公共库（platform 单例项目）。 */
    private long sourceProjectId() {
        return projects.platformProject()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "公共库项目（platform）不存在，无法回填"))
                .id();
    }

    public Preview preview(long targetProjectId) {
        access.bindProject(targetProjectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        long source = sourceProjectId();
        Plan plan = plan(source, targetProjectId);
        return new Preview(source, targetProjectId, plan.scanned(), plan.fill().size(),
                plan.reasons().values().stream().mapToInt(Integer::intValue).sum(),
                plan.reasons(), plan.samples());
    }

    @Transactional
    public Applied apply(long targetProjectId) {
        access.bindProject(targetProjectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        long source = sourceProjectId();
        Plan plan = plan(source, targetProjectId);
        Long operator = TenantContext.userId();
        int skipped = plan.reasons().values().stream().mapToInt(Integer::intValue).sum();

        int filled = 0;
        for (Fill fill : plan.fill()) {
            Row target = fill.target();
            Row from = fill.source();
            int changed = jdbc.update("""
                    UPDATE test_case SET
                        precondition = CASE WHEN precondition IS NULL OR precondition = ''
                                            THEN ? ELSE precondition END,
                        expected = CASE WHEN expected IS NULL OR expected = ''
                                        THEN ? ELSE expected END,
                        case_kind = CASE WHEN case_kind IS NULL OR case_kind = ''
                                         THEN ? ELSE case_kind END,
                        inherited_from = ?, updated_by = ?
                     WHERE id = ? AND project_id = ?
                    """, from.precondition(), from.expected(), from.caseKind(),
                    from.id(), operator, target.id(), targetProjectId);
            if (changed > 0) {
                filled++;
            }
            // 步骤只在目标没有步骤时复制：已有步骤说明本地维护过，覆盖会丢掉人工补充。
            if (target.steps() == 0) {
                jdbc.update("""
                        INSERT INTO test_case_step (test_case_id, step_no, action, expectation,
                                                    source_row, created_by, updated_by)
                        SELECT ?, step_no, action, expectation, source_row, ?, ?
                          FROM test_case_step WHERE test_case_id = ?
                        """, target.id(), operator, operator, from.id());
            }
        }

        String reasonsJson = toJson(plan.reasons());
        jdbc.update("""
                INSERT INTO case_backfill_batch (source_project_id, target_project_id, mode,
                    scanned_count, filled_count, skipped_count, reasons_json, created_by)
                VALUES (?, ?, 'APPLY', ?, ?, ?, ?, ?)
                """, source, targetProjectId, plan.scanned(), filled, skipped, reasonsJson, operator);
        Long batchId = jdbc.queryForObject("SELECT MAX(id) FROM case_backfill_batch WHERE "
                + "target_project_id = ?", Long.class, targetProjectId);
        return new Applied(batchId == null ? 0 : batchId, source, targetProjectId,
                plan.scanned(), filled, skipped, plan.reasons());
    }

    // ------------------------------------------------------------------ 内部

    private record Fill(Row target, Row source) {
    }

    private record Plan(int scanned, List<Fill> fill, Map<String, Integer> reasons,
                        List<String> samples) {
    }

    private Plan plan(long sourceProjectId, long targetProjectId) {
        Map<String, Row> source = index(sourceProjectId);
        List<Row> targets = rows(targetProjectId);
        List<Fill> fill = new ArrayList<>();
        Map<String, Integer> reasons = new LinkedHashMap<>();
        List<String> samples = new ArrayList<>();
        for (Row target : targets) {
            String code = target.caseCode() == null ? "" : target.caseCode().trim();
            Row from = source.get(code);
            if (from == null) {
                reasons.merge("NO_SOURCE", 1, Integer::sum);
                continue;
            }
            // 幂等判据 = 「这一条还有没有东西可填」。只判「目标三列都有值」不够：
            // 源侧本身没有 case_kind 时目标永远补不出来，会被每轮 preview 重复算成「将填充」。
            boolean canFillPre = isBlank(target.precondition()) && !isBlank(from.precondition());
            boolean canFillExp = isBlank(target.expected()) && !isBlank(from.expected());
            boolean canFillKind = isBlank(target.caseKind()) && !isBlank(from.caseKind());
            boolean canFillSteps = target.steps() == 0 && from.steps() > 0;
            if (!canFillPre && !canFillExp && !canFillKind && !canFillSteps) {
                if (hasAll(target) && target.steps() > 0) {
                    reasons.merge("ALREADY_FILLED", 1, Integer::sum);
                } else {
                    reasons.merge("SOURCE_EMPTY", 1, Integer::sum);
                }
                continue;
            }
            fill.add(new Fill(target, from));
            if (samples.size() < 10) {
                samples.add(code);
            }
        }
        return new Plan(targets.size(), fill, reasons, samples);
    }

    private static boolean hasAll(Row row) {
        return !isBlank(row.precondition()) && !isBlank(row.expected())
                && !isBlank(row.caseKind());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Row> index(long projectId) {
        Map<String, Row> out = new LinkedHashMap<>();
        for (Row row : rows(projectId)) {
            if (row.caseCode() != null && !row.caseCode().isBlank()) {
                out.putIfAbsent(row.caseCode().trim(), row);
            }
        }
        return out;
    }

    private List<Row> rows(long projectId) {
        return jdbc.query("""
                SELECT t.id, t.case_code, t.precondition, t.expected, t.case_kind,
                       (SELECT COUNT(*) FROM test_case_step s WHERE s.test_case_id = t.id) AS steps
                  FROM test_case t
                 WHERE t.project_id = ? AND t.deleted_at IS NULL
                 ORDER BY t.id
                """, (rs, i) -> new Row(rs.getLong("id"), rs.getString("case_code"),
                rs.getString("precondition"), rs.getString("expected"),
                rs.getString("case_kind"), rs.getInt("steps")), projectId);
    }

    /** 原因分布写成小 JSON；键固定三种，手工拼即可，不引序列化器。 */
    private static String toJson(Map<String, Integer> reasons) {
        StringBuilder sb = new StringBuilder("{");
        reasons.forEach((key, value) -> {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(key).append("\":").append(value);
        });
        return sb.append('}').toString();
    }
}
