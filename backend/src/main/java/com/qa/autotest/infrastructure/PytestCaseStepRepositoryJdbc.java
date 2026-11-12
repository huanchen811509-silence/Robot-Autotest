package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.PytestCaseStepRepository;
import com.qa.autotest.domain.caseasset.PytestCaseStep;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PytestCaseStepRepository} 的 JDBC 实现。
 *
 * <p>写入用单条多值 INSERT 而不是批量批处理：一次 run 的步骤数在千级以内，
 * 一条语句不会撑爆 max_allowed_packet（每行正文已在上层截断到 4000 字符），
 * 而且事务边界更清晰——要么整份证据进库，要么一条都不进。
 */
@Repository
public class PytestCaseStepRepositoryJdbc implements PytestCaseStepRepository {

    private static final int MAX_ROWS_PER_INSERT = 200;

    private static final String COLUMNS = "run_id, test_result_id, node_id, case_key, seq, kind, "
            + "label, content, status, command_id, stdout_path, log_line, source";

    private static final RowMapper<PytestCaseStep> MAPPER = (rs, rowNum) -> new PytestCaseStep(
            rs.getLong("id"),
            rs.getLong("run_id"),
            (Long) rs.getObject("test_result_id"),
            rs.getString("node_id"),
            rs.getString("case_key"),
            rs.getInt("seq"),
            rs.getString("kind"),
            rs.getString("label"),
            rs.getString("content"),
            rs.getString("status"),
            rs.getString("command_id"),
            rs.getString("stdout_path"),
            rs.getLong("log_line"),
            rs.getString("source"));

    private final JdbcTemplate jdbc;

    public PytestCaseStepRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public int replace(long runId, List<PytestCaseStep> steps) {
        deleteByRun(runId);
        if (steps == null || steps.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (int start = 0; start < steps.size(); start += MAX_ROWS_PER_INSERT) {
            int end = Math.min(start + MAX_ROWS_PER_INSERT, steps.size());
            written += insertChunk(steps.subList(start, end));
        }
        return written;
    }

    private int insertChunk(List<PytestCaseStep> chunk) {
        StringBuilder sql = new StringBuilder("INSERT INTO pytest_case_step (").append(COLUMNS)
                .append(") VALUES ");
        List<Object> args = new ArrayList<>(chunk.size() * 13);
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("(?,?,?,?,?,?,?,?,?,?,?,?,?)");
            PytestCaseStep step = chunk.get(i);
            args.add(step.runId());
            args.add(step.testResultId());
            args.add(step.nodeId());
            args.add(step.caseKey() == null ? "" : step.caseKey());
            args.add(step.seq());
            args.add(step.kind());
            args.add(step.label() == null ? "" : step.label());
            args.add(step.content());
            args.add(step.status());
            args.add(step.commandId());
            args.add(step.stdoutPath());
            args.add(step.logLine());
            args.add(step.source());
        }
        return jdbc.update(sql.toString(), args.toArray());
    }

    @Override
    public List<PytestCaseStep> listByRun(long runId) {
        return jdbc.query("SELECT * FROM pytest_case_step WHERE run_id = ? ORDER BY node_id, seq",
                MAPPER, runId);
    }

    @Override
    public List<PytestCaseStep> listByTestResult(long runId, long testResultId) {
        return jdbc.query("SELECT * FROM pytest_case_step WHERE run_id = ? AND test_result_id = ? "
                + "ORDER BY seq", MAPPER, runId, testResultId);
    }

    @Override
    public int countByRun(long runId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pytest_case_step WHERE run_id = ?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    @Override
    public int deleteByRun(long runId) {
        return jdbc.update("DELETE FROM pytest_case_step WHERE run_id = ?", runId);
    }

    @Override
    public java.util.Map<String, Long> testResultIdsByCaseKey(long runId) {
        java.util.Map<String, Long> result = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT id, case_key FROM test_result WHERE run_id = ?",
                rs -> {
                    result.putIfAbsent(rs.getString("case_key"), rs.getLong("id"));
                }, runId);
        return result;
    }
}
