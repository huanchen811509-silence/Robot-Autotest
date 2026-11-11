package com.qa.autotest.application.caseasset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** OPT-07 / FR-49-26..28：父子组合校验、引用删除拒绝、非唯一匹配不写入。 */
class ProjectCaseCategoryTest {

    private static GlobalCaseService service(JdbcTemplate jdbc) {
        return new GlobalCaseService(jdbc, new ObjectMapper(),
                mock(PlatformProjectService.class), mock(AccessControlService.class),
                mock(AuditService.class));
    }

    /** surefire cwd = backend/，也兼容从仓库根执行。 */
    private static Path repoFile(String relative) {
        Path fromBackend = Path.of("..", relative);
        if (Files.isRegularFile(fromBackend)) {
            return fromBackend;
        }
        return Path.of(relative);
    }

    private static String code(String sql) {
        return sql.lines()
                .filter(l -> !l.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    @Test
    void 非法父子组合被拒() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(20L), eq(7L)))
                .thenReturn(List.of(Map.of("parent_id", 99L)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service(jdbc).validateCategoryPair(7L, 10L, 20L));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode(), "非法组合必须归一到 4xx");
        assertTrue(ex.getMessage().contains("非法父子组合"));
    }

    @Test
    void 合法父子组合放行() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(20L), eq(7L)))
                .thenReturn(List.of(Map.of("parent_id", 10L)));
        assertDoesNotThrow(() -> service(jdbc).validateCategoryPair(7L, 10L, 20L));
    }

    @Test
    void 删有引用分类被拒并回传引用数() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(3L), eq(7L)))
                .thenReturn(List.of(Map.of("name", "基础管理测试")));
        when(jdbc.queryForObject(anyString(), eq(Long.class),
                any(), any(), any(), any(), any())).thenReturn(7L);
        GlobalCaseService.CategoryDeleteResult result = service(jdbc).deleteCategoryInScope(7L, 3L);
        assertFalse(result.deleted(), "有引用不得删除");
        assertEquals(7L, result.referenceCount(), "响应必须带引用数");
        verify(jdbc, never()).update(contains("DELETE FROM case_category"), eq(3L), eq(7L));
    }

    @Test
    void 非唯一匹配不写入且标记人工复核() throws Exception {
        String v42 = Files.readString(
                repoFile("db/migration/V11_42__project_case_category_tighten.sql"),
                StandardCharsets.UTF_8);
        String up = code(v42);
        assertTrue(up.contains("HAVING COUNT(*) = 1"), "只有唯一匹配才回填 id");
        assertTrue(up.contains("category_l1_id IS NULL"), "只回填仍为 NULL 的列（可重复执行）");
        assertTrue(up.contains("category_l2_id IS NULL"));
        assertTrue(up.contains("HAVING COUNT(*) > 1"), "非唯一匹配单独识别");
        assertTrue(up.contains("category_match_review = 'AMBIGUOUS'"),
                "非唯一只标记人工复核、绝不写 id");
        assertFalse(up.toUpperCase(Locale.ROOT).contains("NOT NULL"),
                "不得直接 NOT NULL 造成存量 1798 行失败");

        String v41 = Files.readString(
                repoFile("db/migration/V11_41__project_case_category.sql"),
                StandardCharsets.UTF_8);
        assertTrue(v41.contains("`category_l1_id` BIGINT NULL"), "一级 id 列必须可空");
        assertTrue(v41.contains("`category_l2_id` BIGINT NULL"), "二级 id 列必须可空");

        // Java 运维入口与迁移同口径，可重复执行。
        String java = Files.readString(repoFile(
                "src/main/java/com/qa/autotest/application/caseasset/GlobalCaseService.java"),
                StandardCharsets.UTF_8);
        assertTrue(java.contains("HAVING COUNT(*) = 1"));
        assertTrue(java.contains("category_match_review = 'AMBIGUOUS'"));
    }
}
