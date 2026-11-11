package com.qa.autotest.application.group;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 项目模板 / 归档 + 一层测试场景编排（IAMX-04，D5：不做 DAG）。 */
@Service
public class ScenarioService {

    private final JdbcTemplate jdbc;

    public ScenarioService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 纯校验入口（SCEN-01）：一层编排不接受任何非空 parent_scenario_id。
     * 非空时抛出 INVALID_ARGUMENT，消息带稳定错误码与中文说明；空值直接放行。
     */
    public void validateNoParent(Long parentScenarioId) {
        if (parentScenarioId != null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "INVALID_ARGUMENT(40001)：一层编排不允许嵌套父场景，"
                            + "parent_scenario_id 必须为空，收到 " + parentScenarioId);
        }
    }

    /** 保留原 3 参重载，兼容既有单测与调用方（step_order 取默认 0）。 */
    public long createScenario(long projectId, String name, List<Long> caseSetIds) {
        return createScenario(projectId, name, caseSetIds, 0);
    }

    /** 按 step_order 建场景（SCEN-01：排序锚点显式化）。 */
    public long createScenario(long projectId, String name, List<Long> caseSetIds, int stepOrder) {
        return createScenario(projectId, name, caseSetIds, stepOrder, null);
    }

    /** 一层编排完整入口：先拒绝嵌套父场景，再写库；INSERT 永远把父场景固定为 NULL。 */
    public long createScenario(long projectId, String name, List<Long> caseSetIds,
                               int stepOrder, Long parentScenarioId) {
        validateNoParent(parentScenarioId);
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "场景名不能为空");
        }
        if (caseSetIds == null || caseSetIds.size() > 50) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "用例集数量须在 1~50");
        }
        for (Long id : caseSetIds) {
            if (id == null || id <= 0) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "非法用例集 id");
            }
        }
        String joined = String.join(",", caseSetIds.stream().map(String::valueOf).toList());
        jdbc.update("INSERT INTO test_scenario "
                        + "(project_id, name, case_set_ids, parent_scenario_id) "
                        + "VALUES (?, ?, ?, NULL)",
                projectId, name, joined);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long newId = id == null ? 0L : id;
        jdbc.update("UPDATE test_scenario SET step_order = ? WHERE id = ?", stepOrder, newId);
        return newId;
    }

    public List<Map<String, Object>> scenarios(long projectId) {
        return jdbc.queryForList("SELECT id, name, case_set_ids, step_order, parent_scenario_id "
                + "FROM test_scenario WHERE project_id = ? ORDER BY step_order, id", projectId);
    }

    /** DAG 检测：本版只允许一层，任何 parent_scenario_id 非空都视为违规数据。 */
    public List<String> assertOneLevel(long projectId) {
        List<Map<String, Object>> rows = scenarios(projectId);
        List<String> violations = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get("parent_scenario_id") != null) {
                violations.add("场景 " + row.get("id") + " 存在嵌套父场景");
            }
        }
        return violations;
    }

    /**
     * FR-49-29：创建场景 —— 复用 {@code test_set.kind='scenario'} 三态作为场景容器，
     * 编组明细仍写 {@code test_scenario.case_set_ids} 逗号串（一层，parent_scenario_id 恒 NULL）。
     * 不新建表、不把 case_set_ids 改成中间表；返回 test_scenario.id。
     */
    public long createScenarioGroup(long projectId, String setCode, String name,
                                    List<Long> caseSetIds) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "场景名不能为空");
        }
        if (setCode == null || setCode.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "场景编号不能为空");
        }
        String joined = joinCaseSetIds(caseSetIds);
        jdbc.update("""
                INSERT INTO test_set (project_id, set_code, name, description, kind, created_by)
                VALUES (?, ?, ?, '', 'scenario', ?)
                """, projectId, setCode.trim(), name.trim(), TenantContext.userId());
        Long setId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long sid = setId == null ? 0L : setId;
        jdbc.update("""
                INSERT INTO test_scenario (project_id, name, case_set_ids, parent_scenario_id,
                                           step_order, set_id)
                VALUES (?, ?, ?, NULL, 0, ?)
                """, projectId, name.trim(), joined, sid);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    /** FR-49-29：编组 —— 只改一层场景的 case_set_ids 逗号串，父场景固定为 NULL。 */
    public void regroupCaseSets(long projectId, long scenarioId, List<Long> caseSetIds) {
        String joined = joinCaseSetIds(caseSetIds);
        int changed = jdbc.update("""
                UPDATE test_scenario SET case_set_ids = ?, parent_scenario_id = NULL
                 WHERE id = ? AND project_id = ?
                """, joined, scenarioId, projectId);
        if (changed == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "场景不存在");
        }
    }

    /** FR-49-29：删除场景 —— 连同其 kind=scenario 的 test_set 行与成员一起删。 */
    public void deleteScenarioGroup(long projectId, long scenarioId) {
        List<Long> setIds = jdbc.queryForList(
                "SELECT set_id FROM test_scenario"
                        + " WHERE id = ? AND project_id = ? AND set_id IS NOT NULL",
                Long.class, scenarioId, projectId);
        jdbc.update("DELETE FROM test_scenario WHERE id = ? AND project_id = ?",
                scenarioId, projectId);
        for (Long setId : setIds) {
            jdbc.update("DELETE FROM test_set_item WHERE test_set_id = ?", setId);
            jdbc.update("DELETE FROM test_set"
                    + " WHERE id = ? AND project_id = ? AND kind = 'scenario'",
                    setId, projectId);
        }
    }

    /** 与 createScenario 相同的一层边界：用例集数量 1~50、id 必须为正。 */
    private static String joinCaseSetIds(List<Long> caseSetIds) {
        if (caseSetIds == null || caseSetIds.isEmpty()) {
            return "";
        }
        if (caseSetIds.size() > 50) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "用例集数量须在 1~50");
        }
        for (Long id : caseSetIds) {
            if (id == null || id <= 0) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "非法用例集 id");
            }
        }
        return String.join(",", caseSetIds.stream().map(String::valueOf).toList());
    }

    public long createTemplate(String name, String configJson) {
        jdbc.update("INSERT INTO project_template (name, config_json) VALUES (?, ?)",
                name, configJson);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? 0L : id;
    }

    public int archiveTemplate(long templateId) {
        return jdbc.update("UPDATE project_template SET archived = 1 WHERE id = ?", templateId);
    }

    public Map<String, Object> templates() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("active", jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_template WHERE archived = 0", Integer.class));
        result.put("archived", jdbc.queryForObject(
                "SELECT COUNT(*) FROM project_template WHERE archived = 1", Integer.class));
        return result;
    }
}
