package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.TestReportRepository;
import com.qa.autotest.common.db.SlowQuery;
import com.qa.autotest.domain.report.ReportSnapshot;
import com.qa.autotest.domain.report.ReportSourceType;
import com.qa.autotest.domain.report.ReportState;
import com.qa.autotest.domain.report.TestReport;
import com.qa.autotest.domain.report.TestReportExport;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** 测试报告仓储 JDBC 实现（RPTX-01）。 */
@Repository
public class TestReportRepositoryJdbc implements TestReportRepository {

    /** 显式投影（FR-3.2-02）：快照 JSON/HTML 体积大，列表页不允许顺手带出未用列。 */
    private static final String REPORT_COLUMNS = "id, project_id, run_id, source_type, case_set_id, "
            + "title, state, version, snapshot_hash, snapshot_json, snapshot_html, source_summary, "
            + "created_at, frozen_at";
    private static final String EXPORT_COLUMNS = "id, report_id, format, file_name, size_bytes, "
            + "watermark, created_at";

    private static final RowMapper<TestReport> REPORT = (rs, n) -> new TestReport(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getString("run_id"),
            ReportSourceType.parse(rs.getString("source_type")),
            rs.getObject("case_set_id") == null ? null : rs.getLong("case_set_id"),
            rs.getString("title"),
            ReportState.parse(rs.getString("state")),
            rs.getInt("version"),
            rs.getString("snapshot_hash"),
            rs.getString("snapshot_json"),
            rs.getString("snapshot_html"),
            rs.getString("source_summary"),
            instant(rs, "created_at"),
            instant(rs, "frozen_at"));

    private static final RowMapper<TestReportExport> EXPORT = (rs, n) -> new TestReportExport(
            rs.getLong("id"),
            rs.getLong("report_id"),
            rs.getString("format"),
            rs.getString("file_name"),
            rs.getLong("size_bytes"),
            rs.getString("watermark"),
            instant(rs, "created_at"));

    private final JdbcTemplate jdbc;

    public TestReportRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    @Override
    public Optional<TestReport> find(long id) {
        return jdbc.query("SELECT " + REPORT_COLUMNS + " FROM test_report WHERE id = ?",
                REPORT, id).stream().findFirst();
    }

    @Override
    public List<TestReport> listByProject(long projectId) {
        String sql = "SELECT " + REPORT_COLUMNS
                + " FROM test_report WHERE project_id = ? ORDER BY id DESC";
        return SlowQuery.measure("report.list", sql, () -> jdbc.query(sql, REPORT, projectId));
    }

    @Override
    public int maxVersion(long projectId, String runId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM test_report WHERE project_id = ? AND run_id = ?",
                Integer.class, projectId, runId);
        return value == null ? 0 : value;
    }

    @Override
    public long insert(long projectId, String runId, ReportSourceType sourceType, Long caseSetId,
                       String title, int version, ReportSnapshot snapshot) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO test_report (project_id, run_id, source_type, case_set_id, title, "
                            + "state, version, snapshot_hash, snapshot_json, snapshot_html, source_summary) "
                            + "VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, projectId);
            ps.setString(2, runId);
            ps.setString(3, sourceType.name());
            if (caseSetId == null) {
                ps.setNull(4, java.sql.Types.BIGINT);
            } else {
                ps.setLong(4, caseSetId);
            }
            ps.setString(5, title);
            ps.setInt(6, version);
            ps.setString(7, snapshot.hash());
            ps.setString(8, snapshot.json());
            ps.setString(9, snapshot.html());
            ps.setString(10, snapshot.sourceSummary());
            return ps;
        }, keys);
        return keys.getKey() == null ? 0L : keys.getKey().longValue();
    }

    @Override
    public void freeze(long id, ReportSnapshot snapshot) {
        // version 必须跟着快照一起推进：freeze 里 `report.version() + 1` 生成的是**新版本**的快照，
        // 只写 snapshot_* 不写 version 会让「库里 v1 / 快照里 v2」长期不一致，
        // uk_report_run_version 也就形同虚设（TPLX-02 真实 HTTP 验收 TPLX2-05 抓到的）。
        jdbc.update("UPDATE test_report SET state = 'FROZEN', version = ?, snapshot_hash = ?, "
                        + "snapshot_json = ?, snapshot_html = ?, "
                        + "frozen_at = CURRENT_TIMESTAMP(3) WHERE id = ?",
                snapshot.version(), snapshot.hash(), snapshot.json(), snapshot.html(), id);
    }

    @Override
    public void changeState(long id, ReportState state) {
        jdbc.update("UPDATE test_report SET state = ? WHERE id = ?", state.name(), id);
    }

    @Override
    public long insertExport(long reportId, String format, String fileName, long sizeBytes,
                             String watermark) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO test_report_export (report_id, format, file_name, size_bytes, watermark) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, reportId);
            ps.setString(2, format);
            ps.setString(3, fileName);
            ps.setLong(4, sizeBytes);
            ps.setString(5, watermark);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0L : keys.getKey().longValue();
    }

    @Override
    public List<TestReportExport> exports(long reportId) {
        return jdbc.query("SELECT " + EXPORT_COLUMNS
                        + " FROM test_report_export WHERE report_id = ? ORDER BY id DESC",
                EXPORT, reportId);
    }

    @Override
    public int purgeExportsBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM test_report_export WHERE created_at < ?",
                Timestamp.from(cutoff));
    }

    @Override
    public void audit(long reportId, String action, String fromState, String toState, Long actorId) {
        jdbc.update("INSERT INTO test_report_audit "
                        + "(report_id, action, from_state, to_state, actor_id) VALUES (?, ?, ?, ?, ?)",
                reportId, action, fromState, toState, actorId);
    }
}
