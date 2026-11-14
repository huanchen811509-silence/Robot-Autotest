package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.common.PageResult;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 用例集仓储实现。 */
@Repository
public class TestSetRepositoryJdbc implements TestSetRepository {

    private static final String SELECT = """
            SELECT s.id, s.project_id, s.set_code, s.name, s.description, s.owner_id, s.set_status,
                   (SELECT COUNT(*) FROM test_set_item i WHERE i.test_set_id = s.id) AS item_count
              FROM test_set s
            """;

    private static final RowMapper<TestSet> MAPPER = (rs, rowNum) -> new TestSet(
            rs.getLong("id"), rs.getLong("project_id"), rs.getString("set_code"),
            rs.getString("name"), rs.getString("description"),
            rs.getObject("owner_id") == null ? null : rs.getLong("owner_id"),
            rs.getString("set_status"), rs.getInt("item_count"));

    private final JdbcTemplate jdbc;

    public TestSetRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<TestSet> page(long projectId, String keyword, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE s.project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (s.set_code LIKE ? OR s.name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        String countSql = "SELECT COUNT(*) FROM test_set s" + where;
        TenantGuard.requireProjectScope(countSql, "test_set");
        Long total = jdbc.queryForObject(countSql, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        String listSql = SELECT + where + " ORDER BY s.id DESC LIMIT ? OFFSET ?";
        TenantGuard.requireProjectScope(listSql, "test_set");
        List<TestSet> rows = jdbc.query(listSql, MAPPER, args.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    @Override
    public PageResult<TestSet> platformPage(String keyword, int page, int size) {
        // 保留旧 3 参入口：等价 source=all，避免既有调用方与单测跟着改签名。
        return platformPage(keyword, page, size, "all");
    }

    @Override
    public PageResult<TestSet> platformPage(String keyword, int page, int size, String source) {
        // W57-06：平台集判定收紧为 project_id IS NULL AND owner_scope='PLATFORM'
        //（scope_project_id 是生成列，平台行恒为 0，历史 project_id 为空的 PROJECT 行不算平台集）。
        StringBuilder where = new StringBuilder(
                " WHERE s.project_id IS NULL AND s.owner_scope = 'PLATFORM'");
        List<Object> args = new ArrayList<>();
        if ("sample".equalsIgnoreCase(source)) {
            where.append(" AND s.sample_flag = 1");
        } else if ("manual".equalsIgnoreCase(source)) {
            where.append(" AND (s.sample_flag = 0 OR s.sample_flag IS NULL)");
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (s.set_code LIKE ? OR s.name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        String countSql = "SELECT COUNT(*) FROM test_set s" + where;
        TenantGuard.requireProjectScope(countSql, "test_set");
        Long total = jdbc.queryForObject(countSql, Long.class, args.toArray());
        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(size);
        listArgs.add((page - 1) * size);
        String listSql = SELECT + where + " ORDER BY s.id DESC LIMIT ? OFFSET ?";
        TenantGuard.requireProjectScope(listSql, "test_set");
        List<TestSet> rows = jdbc.query(listSql, MAPPER, listArgs.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    @Override
    public Optional<TestSet> findPlatform(long id) {
        // W57-06：平台集判定统一为 project_id IS NULL AND owner_scope='PLATFORM'。
        String sql = SELECT + " WHERE s.project_id IS NULL AND s.owner_scope = 'PLATFORM'"
                + " AND s.id = ?";
        TenantGuard.requireProjectScope(sql, "test_set");
        return JdbcSupport.first(jdbc.query(sql, MAPPER, id));
    }

    @Override
    public Optional<TestSet> find(long projectId, long id) {
        String sql = SELECT + " WHERE s.project_id = ? AND s.id = ?";
        TenantGuard.requireProjectScope(sql, "test_set");
        return JdbcSupport.first(jdbc.query(sql, MAPPER, projectId, id));
    }

    @Override
    public long insert(TestSet testSet, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO test_set
                      (project_id, set_code, name, description, owner_id, set_status,
                       created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, testSet.projectId());
            ps.setString(2, testSet.setCode());
            ps.setString(3, testSet.name());
            ps.setString(4, testSet.description());
            ps.setObject(5, testSet.ownerId(), java.sql.Types.BIGINT);
            ps.setString(6, testSet.setStatus() == null ? "active" : testSet.setStatus());
            ps.setObject(7, operatorId, java.sql.Types.BIGINT);
            ps.setObject(8, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public long insertWithKind(TestSet testSet, Long operatorId, String kind, Long designId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO test_set
                      (project_id, set_code, name, description, owner_id, set_status,
                       kind, design_id, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, testSet.projectId());
            ps.setString(2, testSet.setCode());
            ps.setString(3, testSet.name());
            ps.setString(4, testSet.description());
            ps.setObject(5, testSet.ownerId(), java.sql.Types.BIGINT);
            ps.setString(6, testSet.setStatus() == null ? "active" : testSet.setStatus());
            ps.setString(7, kind);
            ps.setObject(8, designId, java.sql.Types.BIGINT);
            ps.setObject(9, operatorId, java.sql.Types.BIGINT);
            ps.setObject(10, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public void update(long projectId, long id, String name, String description, Long operatorId) {
        String sql = """
                UPDATE test_set SET name = ?, description = ?, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "test_set");
        jdbc.update(sql, name, description, operatorId, projectId, id);
    }

    @Override
    public void delete(long projectId, long id) {
        String delItems = """
                DELETE FROM test_set_item WHERE test_set_id = ?
                  AND test_set_id IN (SELECT id FROM test_set WHERE project_id = ? AND id = ?)
                """;
        TenantGuard.requireProjectScope(delItems, "test_set_item");
        jdbc.update(delItems, id, projectId, id);
        String delSet = "DELETE FROM test_set WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(delSet, "test_set");
        jdbc.update(delSet, projectId, id);
    }

    @Override
    public List<TestCase> items(long projectId, long testSetId) {
        String sql = """
                SELECT c.id, c.project_id, c.case_code, c.title, c.case_kind, c.priority,
                       c.precondition, c.expected, c.domain_tag, c.review_status, c.source,
                       c.capability, c.description, c.safety_level
                  FROM test_set_item i
                  JOIN test_case c ON c.id = i.test_case_id
                  JOIN test_set s ON s.id = i.test_set_id
                 WHERE s.project_id = ? AND i.test_set_id = ?
                 ORDER BY i.seq_no
                """;
        TenantGuard.requireProjectScope(sql, "test_set_item");
        return jdbc.query(sql, (rs, rowNum) -> new TestCase(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("case_code"),
                rs.getString("title"), rs.getString("case_kind"), rs.getString("priority"),
                rs.getString("precondition"), rs.getString("expected"), rs.getString("domain_tag"),
                rs.getString("review_status"), rs.getString("source"), rs.getString("capability"),
                rs.getString("description"), rs.getString("safety_level")), projectId, testSetId);
    }

    @Override
    public List<TestCase> platformItems(long testSetId) {
        String sql = """
                SELECT c.id, c.project_id, c.case_code, c.title, c.case_kind, c.priority,
                       c.precondition, c.expected, c.domain_tag, c.review_status, c.source,
                       c.capability, c.description, c.safety_level
                  FROM test_set_item i
                  JOIN test_case c ON c.id = i.test_case_id
                  JOIN test_set s ON s.id = i.test_set_id
                 WHERE s.project_id IS NULL AND i.test_set_id = ?
                 ORDER BY i.seq_no
                """;
        TenantGuard.requireProjectScope(sql, "test_set_item");
        return jdbc.query(sql, (rs, rowNum) -> new TestCase(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("case_code"),
                rs.getString("title"), rs.getString("case_kind"), rs.getString("priority"),
                rs.getString("precondition"), rs.getString("expected"), rs.getString("domain_tag"),
                rs.getString("review_status"), rs.getString("source"), rs.getString("capability"),
                rs.getString("description"), rs.getString("safety_level")), testSetId);
    }

    @Override
    public void addItem(long projectId, long testSetId, long testCaseId, int seqNo,
                        Long operatorId) {
        Integer maxSeq = jdbc.queryForObject("""
                SELECT COALESCE(MAX(seq_no), 0) FROM test_set_item
                 WHERE test_set_id IN (SELECT id FROM test_set WHERE project_id = ? AND id = ?)
                """, Integer.class, projectId, testSetId);
        int next = seqNo > 0 ? seqNo : (maxSeq == null ? 1 : maxSeq + 1);
        jdbc.update("""
                INSERT INTO test_set_item (test_set_id, test_case_id, seq_no, item_source,
                                           created_by, updated_by)
                VALUES (?, ?, ?, 'manual', ?, ?)
                """, testSetId, testCaseId, next, operatorId, operatorId);
    }

    @Override
    public void removeItem(long projectId, long testSetId, long testCaseId) {
        String sql = """
                DELETE FROM test_set_item WHERE test_set_id = ? AND test_case_id = ?
                  AND test_set_id IN (SELECT id FROM test_set WHERE project_id = ? AND id = ?)
                """;
        TenantGuard.requireProjectScope(sql, "test_set_item");
        jdbc.update(sql, testSetId, testCaseId, projectId, testSetId);
    }

    @Override
    public void reorder(long projectId, long testSetId, List<Long> caseIds, Long operatorId) {
        // uk_test_set_item = (test_set_id, seq_no)：直接把 A 从 1 挪到 2 会撞上还占着 2 的 B
        //（实测 Duplicate entry）。三段式：先整体平移腾位 → 落新序 → 未点名的接在尾部。
        String shift = """
                UPDATE test_set_item SET seq_no = seq_no + 1000000
                 WHERE test_set_id = ?
                   AND test_set_id IN (SELECT id FROM test_set WHERE project_id = ? AND id = ?)
                """;
        TenantGuard.requireProjectScope(shift, "test_set_item");
        jdbc.update(shift, testSetId, projectId, testSetId);

        String place = """
                UPDATE test_set_item SET seq_no = ?, updated_by = ?
                 WHERE test_set_id = ? AND test_case_id = ?
                   AND test_set_id IN (SELECT id FROM test_set WHERE project_id = ? AND id = ?)
                """;
        TenantGuard.requireProjectScope(place, "test_set_item");
        int seq = 1;
        for (Long caseId : caseIds) {
            jdbc.update(place, seq, operatorId, testSetId, caseId, projectId, testSetId);
            seq++;
        }

        // 只传了部分列表时，剩余条目按原相对顺序接在尾部（否则它们滞留在 100 万段，排序错乱）
        String pull = """
                SELECT id FROM test_set_item
                 WHERE test_set_id = ? AND seq_no > 1000000
                   AND test_set_id IN (SELECT id FROM test_set WHERE project_id = ? AND id = ?)
                 ORDER BY seq_no
                """;
        TenantGuard.requireProjectScope(pull, "test_set_item");
        List<Long> remainder = jdbc.queryForList(pull, Long.class, testSetId, projectId, testSetId);
        for (Long itemId : remainder) {
            String settle = """
                    UPDATE test_set_item SET seq_no = ?, updated_by = ?
                     WHERE id = ?
                       AND test_set_id IN (SELECT id FROM test_set WHERE project_id = ? AND id = ?)
                    """;
            TenantGuard.requireProjectScope(settle, "test_set_item");
            jdbc.update(settle, seq, operatorId, itemId, projectId, testSetId);
            seq++;
        }
    }

    @Override
    public boolean codeExists(long projectId, String setCode, Long excludeId) {
        String sql = """
                SELECT COUNT(*) FROM test_set
                 WHERE project_id = ? AND set_code = ? AND id <> ?
                """;
        TenantGuard.requireProjectScope(sql, "test_set");
        Integer count = jdbc.queryForObject(sql, Integer.class, projectId, setCode,
                excludeId == null ? -1L : excludeId);
        return count != null && count > 0;
    }

    @Override
    public Optional<String> latestRunId(long projectId, long testSetId) {
        // 排序键与前端 RunPicker / 报告下拉一致：运行中的 run 没有 finished_at 也要排在前面，
        // 否则「最近一次」会指向一条更早的已完成 run，用户选完发现内容不对。
        // 尾随 id DESC 只为让同毫秒的两条有确定顺序（否则同一请求两次可能给出不同结果）。
        String sql = """
                SELECT run_id FROM run
                 WHERE project_id = ? AND test_set_id = ?
                 ORDER BY COALESCE(finished_at, started_at, enqueued_at) DESC, id DESC
                 LIMIT 1
                """;
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.queryForList(sql, String.class, projectId, testSetId).stream().findFirst();
    }

    @Override
    public boolean containsRun(long projectId, long testSetId, String runId) {
        String sql = """
                SELECT COUNT(*) FROM run
                 WHERE project_id = ? AND test_set_id = ? AND run_id = ?
                """;
        TenantGuard.requireProjectScope(sql, "run");
        Integer count = jdbc.queryForObject(sql, Integer.class, projectId, testSetId, runId);
        return count != null && count > 0;
    }

    // ---------------------------------------------------------- W57-06 平台用例集可写

    /**
     * 平台集 item 子查询守卫：既限定只动平台集，也让 TenantGuard 看到 project_id 条件。
     * 用裸列 test_set_id（不加 i 别名），兼容单表 DELETE / UPDATE 不接受别名的场景。
     */
    private static final String PLATFORM_ITEM_SCOPE =
            " AND test_set_id IN (SELECT s.id FROM test_set s"
                    + " WHERE s.project_id IS NULL AND s.owner_scope = 'PLATFORM' AND s.id = ?)";

    @Override
    public boolean platformCodeExists(String setCode, Long excludeId) {
        String sql = """
                SELECT COUNT(*) FROM test_set
                 WHERE project_id IS NULL AND owner_scope = 'PLATFORM'
                   AND set_code = ? AND id <> ?
                """;
        TenantGuard.requireProjectScope(sql, "test_set");
        Integer count = jdbc.queryForObject(sql, Integer.class, setCode,
                excludeId == null ? -1L : excludeId);
        return count != null && count > 0;
    }

    @Override
    public long insertPlatform(String setCode, String name, String description, String sampleL1,
                               Long operatorId) {
        final String insertSql = """
                INSERT INTO test_set
                  (project_id, owner_scope, set_code, name, description,
                   sample_flag, sample_l1, user_modified, set_status, created_by, updated_by)
                VALUES (NULL, 'PLATFORM', ?, ?, ?, ?, ?, 0, 'active', ?, ?)
                """;
        TenantGuard.requireProjectScope(insertSql, "test_set");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(insertSql,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, setCode);
            ps.setString(2, name);
            ps.setString(3, description);
            ps.setInt(4, sampleL1 == null ? 0 : 1);
            ps.setString(5, sampleL1);
            ps.setObject(6, operatorId, java.sql.Types.BIGINT);
            ps.setObject(7, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
        if (id > 0) {
            // 平台集自带版本行（project_id=0）：否则 platformReferences 对未登记版本行的集会 404，
            // 新建的集一进列表就把整个 page 拖成 500。
            final String versionSql = """
                    INSERT INTO asset_version
                      (project_id, asset_type, asset_id, asset_version, content_hash, fork_state)
                    VALUES (0, 'TESTSET', ?, 1, NULL, 'SYNCED')
                    """;
            TenantGuard.requireProjectScope(versionSql, "asset_version");
            jdbc.update(versionSql, id);
        }
        return id;
    }

    @Override
    public void updatePlatform(long id, String name, String description, Long operatorId) {
        // 改名即「用户接管」样例集：user_modified=1 让后续 ensureSamples / rebuild 跳过。
        String sql = """
                UPDATE test_set SET name = ?, description = ?, user_modified = 1, updated_by = ?
                 WHERE project_id IS NULL AND owner_scope = 'PLATFORM' AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "test_set");
        jdbc.update(sql, name, description, operatorId, id);
    }

    @Override
    public void deletePlatform(long id) {
        String delItems = """
                DELETE FROM test_set_item WHERE test_set_id = ?
                  AND test_set_id IN (SELECT s.id FROM test_set s
                                      WHERE s.project_id IS NULL
                                        AND s.owner_scope = 'PLATFORM' AND s.id = ?)
                """;
        TenantGuard.requireProjectScope(delItems, "test_set_item");
        jdbc.update(delItems, id, id);
        String delSet = """
                DELETE FROM test_set
                 WHERE project_id IS NULL AND owner_scope = 'PLATFORM' AND id = ?
                """;
        TenantGuard.requireProjectScope(delSet, "test_set");
        jdbc.update(delSet, id);
    }

    @Override
    public List<Long> platformItemIds(long testSetId) {
        String sql = """
                SELECT i.test_case_id FROM test_set_item i
                 WHERE i.test_set_id = ?
                """ + PLATFORM_ITEM_SCOPE + " ORDER BY i.seq_no";
        TenantGuard.requireProjectScope(sql, "test_set_item");
        return jdbc.queryForList(sql, Long.class, testSetId, testSetId);
    }

    @Override
    public int nextPlatformSeqNo(long testSetId) {
        String sql = """
                SELECT COALESCE(MAX(i.seq_no), 0) FROM test_set_item i
                 WHERE i.test_set_id = ?
                """ + PLATFORM_ITEM_SCOPE;
        TenantGuard.requireProjectScope(sql, "test_set_item");
        Integer max = jdbc.queryForObject(sql, Integer.class, testSetId, testSetId);
        return max == null ? 0 : max;
    }

    @Override
    public int addPlatformItemIfAbsent(long testSetId, long testCaseId, int seqNo,
                                       Long operatorId) {
        // INSERT ... SELECT ... WHERE NOT EXISTS：用例不存在 / 已是成员都返回 0，交由服务层计 skipped。
        String sql = """
                INSERT INTO test_set_item
                  (test_set_id, test_case_id, seq_no, item_source, created_by, updated_by)
                SELECT ?, c.id, ?, 'manual', ?, ?
                  FROM test_case c
                 WHERE c.id = ? AND c.project_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM test_set_item x
                                    WHERE x.test_set_id = ? AND x.test_case_id = c.id)
                   AND EXISTS (SELECT 1 FROM test_set s
                                WHERE s.project_id IS NULL
                                  AND s.owner_scope = 'PLATFORM' AND s.id = ?)
                """;
        TenantGuard.requireProjectScope(sql, "test_set_item");
        return jdbc.update(sql, testSetId, seqNo, operatorId, operatorId,
                testCaseId, testSetId, testSetId);
    }

    @Override
    public void removePlatformItem(long testSetId, long testCaseId) {
        String sql = """
                DELETE FROM test_set_item WHERE test_set_id = ? AND test_case_id = ?
                """ + PLATFORM_ITEM_SCOPE;
        TenantGuard.requireProjectScope(sql, "test_set_item");
        jdbc.update(sql, testSetId, testCaseId, testSetId);
    }

    @Override
    public void reorderPlatform(long testSetId, List<Long> caseIds, Long operatorId) {
        // 三段式：先整体平移腾位 → 落新序 → 未点名的接在尾部，避开 uk(test_set_id, seq_no)。
        String shift = """
                UPDATE test_set_item SET seq_no = seq_no + 1000000
                 WHERE test_set_id = ?
                """ + PLATFORM_ITEM_SCOPE;
        TenantGuard.requireProjectScope(shift, "test_set_item");
        jdbc.update(shift, testSetId, testSetId);

        String place = """
                UPDATE test_set_item SET seq_no = ?, updated_by = ?
                 WHERE test_set_id = ? AND test_case_id = ?
                """ + PLATFORM_ITEM_SCOPE;
        TenantGuard.requireProjectScope(place, "test_set_item");
        int seq = 1;
        for (Long caseId : caseIds) {
            jdbc.update(place, seq++, operatorId, testSetId, caseId, testSetId);
        }

        String pull = """
                SELECT i.id FROM test_set_item i
                 WHERE i.test_set_id = ? AND i.seq_no > 1000000
                """ + PLATFORM_ITEM_SCOPE + " ORDER BY i.seq_no";
        TenantGuard.requireProjectScope(pull, "test_set_item");
        List<Long> remainder = jdbc.queryForList(pull, Long.class, testSetId, testSetId);
        for (Long itemId : remainder) {
            String settle = """
                    UPDATE test_set_item SET seq_no = ?, updated_by = ?
                     WHERE id = ? AND test_set_id = ?
                    """ + PLATFORM_ITEM_SCOPE;
            TenantGuard.requireProjectScope(settle, "test_set_item");
            jdbc.update(settle, seq++, operatorId, itemId, testSetId, testSetId);
        }
    }

    @Override
    public List<Long> platformCategoryCaseIds(Long categoryL1Id, Long categoryL2Id) {
        StringBuilder sql = new StringBuilder("""
                SELECT t.id FROM test_case t
                 WHERE t.project_id = (SELECT p.id FROM project p
                                        WHERE p.kind = 'platform' ORDER BY p.id LIMIT 1)
                   AND t.lifecycle_status = 'active'
                """);
        List<Object> args = new ArrayList<>();
        // COALESCE 双轨：id 命中的 active 分类优先；id 为空 / 指向禁用分类时按文本列兜底。
        if (categoryL1Id != null) {
            sql.append("""
                    AND COALESCE(
                          (SELECT c.id FROM case_category c
                            WHERE c.id = t.category_l1_id AND c.level = 1 AND c.status = 'active'),
                          (SELECT c.id FROM case_category c
                            WHERE c.project_id = t.project_id AND c.level = 1
                              AND c.status = 'active' AND c.name = t.category_l1
                            ORDER BY c.id LIMIT 1)) = ?
                    """);
            args.add(categoryL1Id);
        }
        if (categoryL2Id != null) {
            sql.append("""
                    AND COALESCE(
                          (SELECT c.id FROM case_category c
                            WHERE c.id = t.category_l2_id AND c.level = 2 AND c.status = 'active'),
                          (SELECT c.id FROM case_category c
                            WHERE c.project_id = t.project_id AND c.level = 2
                              AND c.status = 'active' AND c.name = t.category_l2
                            ORDER BY c.id LIMIT 1)) = ?
                    """);
            args.add(categoryL2Id);
        }
        sql.append(" ORDER BY t.id");
        TenantGuard.requireProjectScope(sql.toString(), "test_case");
        return jdbc.queryForList(sql.toString(), Long.class, args.toArray());
    }

    @Override
    public void markPlatformUserModified(long testSetId) {
        String sql = """
                UPDATE test_set SET user_modified = 1
                 WHERE project_id IS NULL AND owner_scope = 'PLATFORM'
                   AND sample_flag = 1 AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "test_set");
        jdbc.update(sql, testSetId);
    }
}
