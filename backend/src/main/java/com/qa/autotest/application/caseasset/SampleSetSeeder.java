package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 样例用例集播种（W57-07 / V11_58）。
 *
 * <p>样例集不是新类型，而是「按一级分类派生、可被用户接管的普通平台集」：
 * 幂等键是 {@code (sample_flag=1, sample_l1)}，不是名字；{@code user_modified=1}
 * 表示用户已改名 / 改成员，播种与重建都必须跳过。
 *
 * <p>两类入口：
 * <ul>
 *   <li>{@link #ensureSamples()}：轻量，由平台列表入口每次同步触发，只建缺失的集并填充成员；</li>
 *   <li>{@link #rebuild()}：手动触发，除建缺失集外，还给已存在的样例集补齐缺失成员。</li>
 * </ul>
 * 两者都同步执行（不使用后台线程，避免丢失 TenantContext 鉴权上下文）。
 */
@Service
public class SampleSetSeeder {

    /** 播种计数：created=新建集，skipped=已存在（含用户接管）跳过，addedItems=新增成员。 */
    public record SeedResult(int created, int skipped, int addedItems) {
    }

    // 包可见 + protected 查询方法：单测用 spy 覆盖数据源，无需真实 DB。
    record Category(long id, String name) {
    }

    record ExistingSample(long id, boolean userModified) {
    }

    private final JdbcTemplate jdbc;
    private final TestSetRepository sets;

    public SampleSetSeeder(JdbcTemplate jdbc, TestSetRepository sets) {
        this.jdbc = jdbc;
        this.sets = sets;
    }

    /** 平台列表入口调用：只补齐缺失的样例集。 */
    public SeedResult ensureSamples() {
        return seed(false);
    }

    /** 手动重建：补齐缺失集，并给已存在的样例集补齐缺失成员。 */
    public SeedResult rebuild() {
        return seed(true);
    }

    private SeedResult seed(boolean full) {
        List<Category> categories = loadCategories();
        int created = 0;
        int skipped = 0;
        int addedItems = 0;
        int index = 1;
        for (Category category : categories) {
            ExistingSample existing = findSampleSet(category.name());
            long setId;
            boolean fresh = false;
            if (existing == null) {
                String code = nextSampleCode(index);
                setId = sets.insertPlatform(code, category.name() + " · 样例",
                        "按一级分类「" + category.name() + "」自动派生的样例集",
                        category.name(), TenantContext.userId());
                created++;
                fresh = true;
            } else if (existing.userModified()) {
                // 用户已接管：播种与重建都跳过，绝不覆盖用户的改名 / 成员
                skipped++;
                index++;
                continue;
            } else {
                setId = existing.id();
                skipped++;
            }
            if (fresh || full) {
                addedItems += fillMissingMembers(setId, category.id());
            }
            index++;
        }
        return new SeedResult(created, skipped, addedItems);
    }

    /** level=1 且启用（case_category.status='active'，迁移里没有 enabled 列）的平台域分类。 */
    protected List<Category> loadCategories() {
        String sql = """
                SELECT id, name FROM case_category
                 WHERE level = 1 AND status = 'active'
                   AND (scope IS NULL OR scope = 'platform')
                 ORDER BY sort_no, id
                """;
        return jdbc.query(sql, (rs, i) -> new Category(rs.getLong("id"), rs.getString("name")));
    }

    /** 幂等键 (sample_flag=1, sample_l1)：同一一级分类只认一个样例集。 */
    protected ExistingSample findSampleSet(String sampleL1) {
        String sql = """
                SELECT id, user_modified FROM test_set
                 WHERE project_id IS NULL AND owner_scope = 'PLATFORM'
                   AND sample_flag = 1 AND sample_l1 = ?
                 ORDER BY id LIMIT 1
                """;
        List<ExistingSample> rows = jdbc.query(sql,
                (rs, i) -> new ExistingSample(rs.getLong("id"), rs.getInt("user_modified") == 1),
                sampleL1);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** SAMPLE-<序号>：序号被占用时顺延，保证唯一键不冲突。 */
    private String nextSampleCode(int start) {
        int n = start;
        String code = "SAMPLE-" + n;
        while (sets.platformCodeExists(code, null)) {
            n++;
            code = "SAMPLE-" + n;
        }
        return code;
    }

    /** 把该一级分类下所有 active 用例补进集合；已存在的成员跳过（幂等）。 */
    private int fillMissingMembers(long setId, long categoryL1Id) {
        List<Long> caseIds = sets.platformCategoryCaseIds(categoryL1Id, null);
        if (caseIds == null || caseIds.isEmpty()) {
            return 0;
        }
        Set<Long> seen = new HashSet<>(sets.platformItemIds(setId));
        int seq = sets.nextPlatformSeqNo(setId);
        int added = 0;
        Long operator = TenantContext.userId();
        for (Long caseId : caseIds) {
            if (caseId == null || !seen.add(caseId)) {
                continue;
            }
            if (sets.addPlatformItemIfAbsent(setId, caseId, ++seq, operator) > 0) {
                added++;
            }
        }
        return added;
    }
}
