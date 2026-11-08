package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.caseasset.CapabilityStat;
import com.qa.autotest.domain.caseasset.SafetyLevel;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestCaseStep;
import com.qa.autotest.domain.common.PageResult;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 用例台账仓储实现（ADR-0005）。 */
@Repository
public class TestCaseRepositoryJdbc implements TestCaseRepository {

    private static final String SELECT = """
            SELECT id, project_id, case_code, title, case_kind, priority, precondition, expected,
                   domain_tag, review_status, source, capability, description, safety_level
              FROM test_case
            """;

    private static final RowMapper<TestCase> MAPPER = (rs, rowNum) -> new TestCase(
            rs.getLong("id"), rs.getLong("project_id"), rs.getString("case_code"),
            rs.getString("title"), rs.getString("case_kind"), rs.getString("priority"),
            rs.getString("precondition"), rs.getString("expected"), rs.getString("domain_tag"),
            rs.getString("review_status"), rs.getString("source"), rs.getString("capability"),
            rs.getString("description"), rs.getString("safety_level"));

    private static final RowMapper<TestCaseStep> STEP_MAPPER = (rs, rowNum) -> new TestCaseStep(
            rs.getLong("id"), rs.getLong("test_case_id"), rs.getInt("step_no"),
            rs.getString("action"), rs.getString("expectation"));

    private final JdbcTemplate jdbc;

    public TestCaseRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PageResult<TestCase> page(long projectId, String keyword, String domain, String priority,
                                     String reviewStatus, String capability, String safetyLevel,
                                     int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (case_code LIKE ? OR title LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
        }
        if (domain != null && !domain.isBlank()) {
            where.append(" AND domain_tag = ?");
            args.add(domain);
        }
        if (priority != null && !priority.isBlank()) {
            where.append(" AND priority = ?");
            args.add(priority);
        }
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            where.append(" AND review_status = ?");
            args.add(reviewStatus);
        }
        if (capability != null && !capability.isBlank()) {
            where.append(" AND capability = ?");
            args.add(capability);
        }
        if (safetyLevel != null && !safetyLevel.isBlank()) {
            where.append(" AND safety_level = ?");
            args.add(safetyLevel.trim().toUpperCase(java.util.Locale.ROOT));
        }
        String countSql = "SELECT COUNT(*) FROM test_case" + where;
        TenantGuard.requireProjectScope(countSql, "test_case");
        Long total = jdbc.queryForObject(countSql, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        String listSql = SELECT + where + " ORDER BY id DESC LIMIT ? OFFSET ?";
        TenantGuard.requireProjectScope(listSql, "test_case");
        List<TestCase> rows = jdbc.query(listSql, MAPPER, args.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    @Override
    public Optional<TestCase> find(long projectId, long id) {
        String sql = SELECT + " WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "test_case");
        return JdbcSupport.first(jdbc.query(sql, MAPPER, projectId, id));
    }

    @Override
    public Optional<TestCase> findByCode(long projectId, String caseCode) {
        String sql = SELECT + " WHERE project_id = ? AND case_code = ?";
        TenantGuard.requireProjectScope(sql, "test_case");
        return JdbcSupport.first(jdbc.query(sql, MAPPER, projectId, caseCode));
    }

    @Override
    public long insert(TestCase testCase, Long operatorId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO test_case
                      (project_id, case_code, title, case_kind, source_type, priority, safety_level,
                       domain_tag, status, description, precondition, expected, review_status,
                       source, capability, created_by, updated_by)
                    VALUES (?, ?, ?, ?, 'ledger', ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, testCase.projectId());
            ps.setString(2, testCase.caseCode());
            ps.setString(3, testCase.title());
            ps.setString(4, testCase.category());
            ps.setString(5, testCase.priority());
            ps.setString(6, SafetyLevel.from(testCase.safetyLevel()).name());
            ps.setString(7, testCase.domainTag());
            ps.setString(8, testCase.description());
            ps.setString(9, testCase.precondition());
            ps.setString(10, testCase.expected());
            ps.setString(11, testCase.reviewStatus());
            ps.setString(12, testCase.source());
            ps.setString(13, testCase.capability());
            ps.setObject(14, operatorId, java.sql.Types.BIGINT);
            ps.setObject(15, operatorId, java.sql.Types.BIGINT);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public void update(TestCase testCase, Long operatorId) {
        String sql = """
                UPDATE test_case SET title = ?, case_kind = ?, priority = ?, safety_level = ?,
                       domain_tag = ?, description = ?, precondition = ?, expected = ?,
                       review_status = ?, source = ?, capability = ?, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "test_case");
        jdbc.update(sql, testCase.title(), testCase.category(), testCase.priority(),
                SafetyLevel.from(testCase.safetyLevel()).name(),
                testCase.domainTag(), testCase.description(), testCase.precondition(),
                testCase.expected(), testCase.reviewStatus(), testCase.source(),
                testCase.capability(), operatorId, testCase.projectId(), testCase.id());
    }

    @Override
    public void delete(long projectId, long id) {
        String delSteps = """
                DELETE FROM test_case_step WHERE test_case_id = ?
                  AND test_case_id IN (SELECT id FROM test_case WHERE project_id = ? AND id = ?)
                """;
        TenantGuard.requireProjectScope(delSteps, "test_case_step");
        jdbc.update(delSteps, id, projectId, id);
        String delItems = """
                DELETE FROM test_set_item WHERE test_case_id = ?
                  AND test_case_id IN (SELECT id FROM test_case WHERE project_id = ? AND id = ?)
                """;
        TenantGuard.requireProjectScope(delItems, "test_set_item");
        jdbc.update(delItems, id, projectId, id);
        String delCase = "DELETE FROM test_case WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(delCase, "test_case");
        jdbc.update(delCase, projectId, id);
    }

    @Override
    public List<TestCaseStep> steps(long testCaseId) {
        return jdbc.query("""
                SELECT id, test_case_id, step_no, action, expectation
                  FROM test_case_step WHERE test_case_id = ? ORDER BY step_no
                """, STEP_MAPPER, testCaseId);
    }

    @Override
    public void replaceSteps(long testCaseId, List<TestCaseStep> steps, Long operatorId) {
        jdbc.update("DELETE FROM test_case_step WHERE test_case_id = ?", testCaseId);
        if (steps == null) {
            return;
        }
        for (TestCaseStep step : steps) {
            jdbc.update("""
                    INSERT INTO test_case_step
                      (test_case_id, step_no, action, expectation, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, testCaseId, step.stepNo(), step.action(), step.expectation(),
                    operatorId, operatorId);
        }
    }

    @Override
    public void writeImportMetadata(long testCaseId, ImportMetadata meta, Long operatorId) {
        if (meta == null || (isBlank(meta.categoryL2()) && isBlank(meta.sourcePriority())
                && isBlank(meta.caseClass()) && isBlank(meta.testCategory())
                && isBlank(meta.ed202Coverage()) && isBlank(meta.sourceSheet()))) {
            return;
        }
        jdbc.update("""
                UPDATE test_case SET category_l2 = ?, source_priority = ?, case_class = ?,
                       test_category = ?, ed202_coverage = ?, source_sheet = ?, updated_by = ?
                 WHERE id = ?
                """, meta.categoryL2(), meta.sourcePriority(), meta.caseClass(),
                meta.testCategory(), meta.ed202Coverage(), meta.sourceSheet(),
                operatorId, testCaseId);
    }

    @Override
    public void insertImportedSteps(long testCaseId, List<ImportedStep> steps, int sourceRow,
                                    Long operatorId) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (ImportedStep step : steps) {
            jdbc.update("""
                    INSERT INTO test_case_step
                      (test_case_id, step_no, action, expectation, source_row, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, testCaseId, step.stepNo(), step.action(), step.expectation(),
                    sourceRow, operatorId, operatorId);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public void updateReview(long projectId, long id, String reviewStatus, String comment,
                             Long reviewedBy, Instant reviewedAt) {
        String sql = """
                UPDATE test_case SET review_status = ?, review_comment = ?, reviewed_by = ?,
                       reviewed_at = ?, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "test_case");
        jdbc.update(sql, reviewStatus, comment, reviewedBy,
                reviewedAt == null ? null : Timestamp.from(reviewedAt), reviewedBy, projectId, id);
    }

    @Override
    public void updatePriority(long projectId, long id, String priority, Long operatorId) {
        String sql = """
                UPDATE test_case SET priority = ?, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "test_case");
        jdbc.update(sql, priority, operatorId, projectId, id);
    }

    @Override
    public void updateDomainTag(long projectId, long id, String domainTag, Long operatorId) {
        String sql = """
                UPDATE test_case SET domain_tag = ?, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "test_case");
        jdbc.update(sql, domainTag, operatorId, projectId, id);
    }

    @Override
    public void updateSafetyLevel(long projectId, long id, String safetyLevel, Long operatorId) {
        String sql = """
                UPDATE test_case SET safety_level = ?, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "test_case");
        jdbc.update(sql, SafetyLevel.from(safetyLevel).name(), operatorId, projectId, id);
    }

    @Override
    public boolean codeExists(long projectId, String caseCode, Long excludeId) {
        String sql = """
                SELECT COUNT(*) FROM test_case
                 WHERE project_id = ? AND case_code = ? AND id <> ?
                """;
        TenantGuard.requireProjectScope(sql, "test_case");
        Integer count = jdbc.queryForObject(sql, Integer.class, projectId, caseCode,
                excludeId == null ? -1L : excludeId);
        return count != null && count > 0;
    }

    @Override
    public long countByProject(long projectId) {
        String sql = "SELECT COUNT(*) FROM test_case WHERE project_id = ?";
        TenantGuard.requireProjectScope(sql, "test_case");
        Long count = jdbc.queryForObject(sql, Long.class, projectId);
        return count == null ? 0 : count;
    }

    @Override
    public List<CapabilityStat> capabilities(long projectId) {
        // 空白串在库里既不等于 NULL 也不等于 ''，会被当成能力桶渲染出空标签，故按 trim 后判空
        String sql = """
                SELECT capability, COUNT(*) AS cnt FROM test_case
                 WHERE project_id = ? AND capability IS NOT NULL AND TRIM(capability) <> ''
                 GROUP BY capability ORDER BY cnt DESC, capability
                """;
        TenantGuard.requireProjectScope(sql, "test_case");
        return jdbc.query(sql, (rs, rowNum) -> new CapabilityStat(
                rs.getString("capability"), rs.getLong("cnt")), projectId);
    }
}
