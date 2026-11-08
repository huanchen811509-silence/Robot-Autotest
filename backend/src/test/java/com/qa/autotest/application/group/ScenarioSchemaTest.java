package com.qa.autotest.application.group;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** SCEN-01：一层编排的 schema 外键与纯校验入口静态断言（纯逻辑 + 读源码，不连数据库）。 */
class ScenarioSchemaTest {

    /** surefire cwd = backend/，也兼容从仓库根执行。 */
    private static Path repoFile(String relative) {
        Path fromBackend = Path.of("..", relative);
        if (Files.isRegularFile(fromBackend)) {
            return fromBackend;
        }
        return Path.of(relative);
    }

    private static String scenarioSource() throws Exception {
        return Files.readString(repoFile(
                "src/main/java/com/qa/autotest/application/group/ScenarioService.java"));
    }

    private static ScenarioService withoutDb() {
        return new ScenarioService(null);
    }

    @Test
    void 迁移含外键与step_order() throws Exception {
        String sql = Files.readString(
                repoFile("db/migration/V11_11__test_scenario_order.sql"));
        assertTrue(sql.contains("fk_scenario_project"), "场景必须挂在真实项目上");
        assertTrue(sql.contains("step_order"), "步骤排序锚点必须显式化");
        assertTrue(sql.toUpperCase(Locale.ROOT).contains("FOREIGN KEY"),
                "外键必须由迁移真实建立");
        assertTrue(sql.contains("idx_test_scenario_order"), "排序索引必须存在");
    }

    @Test
    void validateNoParent拒绝非空放行空() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> withoutDb().validateNoParent(1L));
        assertTrue(e.errorCode() == ErrorCode.INVALID_ARGUMENT,
                "非空父场景必须归一到 INVALID_ARGUMENT");
        assertTrue(e.getMessage().contains("INVALID_ARGUMENT"),
                "异常消息必须带稳定错误码");
        assertTrue(e.getMessage().contains("父场景"),
                "异常消息必须带中文说明");
        assertDoesNotThrow(() -> withoutDb().validateNoParent(null),
                "空父场景必须放行");
    }

    @Test
    void 源码保持一层编排的INSERT形态() throws Exception {
        String text = scenarioSource();
        assertTrue(text.contains("VALUES (?, ?, ?, NULL)"),
                "createScenario 必须把 parent_scenario_id 固定为 NULL");
        assertFalse(text.contains("parent_scenario_id = ?"),
                "不允许出现按参数写父场景的语句");
    }

    @Test
    void scenarios按step_order排序并查出step_order() throws Exception {
        String text = scenarioSource();
        assertTrue(text.contains("ORDER BY step_order, id"),
                "scenarios 必须按 step_order, id 排序");
        assertTrue(text.contains("SELECT id, name, case_set_ids, step_order, parent_scenario_id"),
                "scenarios 必须把 step_order 查出来");
    }

    @Test
    void 创建链路调用校验且存在stepOrder重载() throws Exception {
        String text = scenarioSource();
        assertTrue(text.contains("int stepOrder"),
                "必须存在按 step_order 建场景的重载");
        assertTrue(text.contains("validateNoParent(parentScenarioId)"),
                "完整创建入口必须调用纯校验入口");
    }
}
