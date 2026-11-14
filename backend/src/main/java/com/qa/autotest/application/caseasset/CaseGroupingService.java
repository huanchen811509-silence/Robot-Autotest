package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 全局用例的分类归组聚合（W57-08 / GET /api/global/cases/grouped）。
 *
 * <p>口径与全局用例列表筛选保持一致：只数平台公共库（lifecycle_status='active'）用例，
 * 用 {@code COALESCE} 双轨解析分类 —— 先按 {@code category_l1_id / category_l2_id} 命中
 * {@code status='active'} 的分类，id 为空或指向禁用分类时再按文本列
 * {@code category_l1 / category_l2} 兜底。历史脏数据（只有文本、匹配不到启用分类）
 * 一律落到「未分类」节点，禁用分类绝不出现在结果里。
 *
 * <p>聚合全部由参数化 SQL 完成（占位符 {@code ?}），不拼接任何用户输入。
 */
@Service
public class CaseGroupingService {

    /** 二级分类聚合节点。 */
    public record L2Group(long l2Id, String l2Name, int count) {
    }

    /**
     * 一级分类分组。
     *
     * @param total         该一级分类下用例总数（含未归到二级的）
     * @param uncategorized 该一级分类下没有有效二级分类的用例数
     */
    public record L1Group(long l1Id, String l1Name, List<L2Group> l2s, long total,
                          int uncategorized) {
    }

    // 包可见：单测用 spy 覆盖三个查询方法，无需真实 DB。
    record Category(long id, Long parentId, String name) {
    }

    record AggRow(Long l1Id, Long l2Id, long count) {
    }

    private static final String UNCATEGORIZED_NAME = "未分类";

    private final JdbcTemplate jdbc;
    private final PlatformProjectService platform;
    private final AccessControlService access;

    public CaseGroupingService(JdbcTemplate jdbc, PlatformProjectService platform,
                               AccessControlService access) {
        this.jdbc = jdbc;
        this.platform = platform;
        this.access = access;
    }

    public List<L1Group> grouped() {
        access.requireGlobalEnter();
        access.require(PermissionResource.CASE, PermissionAction.READ);
        long scope = platform.id();
        return assemble(loadL1(scope), loadL2(scope), loadAggregation(scope));
    }

    /** 启用的一级分类（禁用分类不出现）。 */
    protected List<Category> loadL1(long scope) {
        String sql = """
                SELECT id, name FROM case_category
                 WHERE project_id = ? AND level = 1 AND status = 'active'
                   AND (scope IS NULL OR scope = 'platform')
                 ORDER BY sort_no, id
                """;
        return jdbc.query(sql, (rs, i) -> new Category(rs.getLong("id"), null,
                rs.getString("name")), scope);
    }

    /** 启用的二级分类（禁用分类不出现）。 */
    protected List<Category> loadL2(long scope) {
        String sql = """
                SELECT id, parent_id, name FROM case_category
                 WHERE project_id = ? AND level = 2 AND status = 'active'
                   AND (scope IS NULL OR scope = 'platform')
                 ORDER BY sort_no, id
                """;
        return jdbc.query(sql, (rs, i) -> new Category(rs.getLong("id"),
                rs.getObject("parent_id") == null ? null : rs.getLong("parent_id"),
                rs.getString("name")), scope);
    }

    /**
     * 用例按 (l1, l2) 分组计数。COALESCE 双轨：id 命中的启用分类优先；
     * id 为空 / 指向禁用分类时按文本列再匹配一次启用分类；都匹配不到则为 NULL → 未分类。
     */
    protected List<AggRow> loadAggregation(long scope) {
        String sql = """
                SELECT
                  COALESCE(
                    (SELECT c.id FROM case_category c
                      WHERE c.id = t.category_l1_id AND c.level = 1 AND c.status = 'active'),
                    (SELECT c.id FROM case_category c
                      WHERE c.project_id = t.project_id AND c.level = 1
                        AND c.status = 'active' AND c.name = t.category_l1
                      ORDER BY c.id LIMIT 1)) AS l1_id,
                  COALESCE(
                    (SELECT c.id FROM case_category c
                      WHERE c.id = t.category_l2_id AND c.level = 2 AND c.status = 'active'),
                    (SELECT c.id FROM case_category c
                      WHERE c.project_id = t.project_id AND c.level = 2
                        AND c.status = 'active' AND c.name = t.category_l2
                      ORDER BY c.id LIMIT 1)) AS l2_id,
                  COUNT(*) AS cnt
                  FROM test_case t
                 WHERE t.project_id = ? AND t.lifecycle_status = 'active'
                 GROUP BY l1_id, l2_id
                """;
        return jdbc.query(sql, (rs, i) -> new AggRow(
                rs.getObject("l1_id") == null ? null : rs.getLong("l1_id"),
                rs.getObject("l2_id") == null ? null : rs.getLong("l2_id"),
                rs.getLong("cnt")), scope);
    }

    /** 纯组装逻辑（package-private 便于单测）：把聚合行挂到启用分类树下，脏数据落未分类节点。 */
    List<L1Group> assemble(List<Category> l1s, List<Category> l2s, List<AggRow> agg) {
        Map<Long, List<Category>> l2ByParent = new LinkedHashMap<>();
        Set<Long> activeL2Ids = new HashSet<>();
        for (Category l2 : l2s) {
            if (l2.parentId() != null) {
                l2ByParent.computeIfAbsent(l2.parentId(), k -> new ArrayList<>()).add(l2);
            }
            activeL2Ids.add(l2.id());
        }

        Map<Long, Long> l1Total = new HashMap<>();
        Map<Long, Integer> l1Uncategorized = new HashMap<>();
        Map<Long, Map<Long, Integer>> countByL1L2 = new HashMap<>();
        long globalUncategorized = 0L;
        for (AggRow row : agg) {
            long count = row.count();
            if (row.l1Id() == null) {
                // 只有文本 / 分类被禁用：l1 都解析不出来，归「未分类」节点
                globalUncategorized += count;
                continue;
            }
            long l1 = row.l1Id();
            l1Total.merge(l1, count, Long::sum);
            if (row.l2Id() == null || !activeL2Ids.contains(row.l2Id())) {
                l1Uncategorized.merge(l1, (int) count, Integer::sum);
            } else {
                countByL1L2.computeIfAbsent(l1, k -> new HashMap<>())
                        .merge(row.l2Id(), (int) count, Integer::sum);
            }
        }

        List<L1Group> result = new ArrayList<>();
        for (Category l1 : l1s) {
            List<L2Group> groups = new ArrayList<>();
            Map<Long, Integer> counts = countByL1L2.getOrDefault(l1.id(), Map.of());
            for (Category l2 : l2ByParent.getOrDefault(l1.id(), List.of())) {
                groups.add(new L2Group(l2.id(), l2.name(), counts.getOrDefault(l2.id(), 0)));
            }
            result.add(new L1Group(l1.id(), l1.name(), groups,
                    l1Total.getOrDefault(l1.id(), 0L),
                    l1Uncategorized.getOrDefault(l1.id(), 0)));
        }
        if (globalUncategorized > 0) {
            result.add(new L1Group(0L, UNCATEGORIZED_NAME, List.of(), globalUncategorized,
                    (int) globalUncategorized));
        }
        return result;
    }
}
