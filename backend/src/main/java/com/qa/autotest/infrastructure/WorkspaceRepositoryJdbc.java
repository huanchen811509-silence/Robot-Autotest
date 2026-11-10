package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.WorkspaceRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.workspace.WorkspaceJob;
import com.qa.autotest.domain.workspace.WorkspaceSnapshot;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 工作区任务与快照的 JDBC 实现（v4.1）。
 *
 * <p>全部 SQL 带 {@code project_id} 并经 {@link TenantGuard} 守卫；
 * {@code workspace_job.active_lock} 唯一键就是项目锁，重复插入映射为 409。</p>
 */
@Repository
public class WorkspaceRepositoryJdbc implements WorkspaceRepository {

    private static final String JOB_COLUMNS = """
            id, project_id, job_type, granularity, target_path, status, progress,
            bundle_path, strategy, manifest_sha256, report_json, error_message,
            started_at, finished_at, created_at
            """;

    private final JdbcTemplate jdbc;

    public WorkspaceRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long createJob(long projectId, String jobType, String granularity, String targetPath,
                          String strategy, String bundlePath, Long operatorId) {
        String sql = """
                INSERT INTO workspace_job
                  (project_id, job_type, granularity, target_path, status, progress,
                   bundle_path, strategy, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?)
                """;
        TenantGuard.requireProjectScope(sql, "workspace_job");
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, projectId);
                ps.setString(2, jobType);
                ps.setString(3, granularity);
                ps.setString(4, targetPath);
                ps.setString(5, bundlePath);
                ps.setString(6, strategy);
                ps.setObject(7, operatorId, java.sql.Types.BIGINT);
                ps.setObject(8, operatorId, java.sql.Types.BIGINT);
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException exc) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该项目已有进行中的" + (isExport(jobType) ? "导出" : "导入") + "任务，请等待完成或重试");
        }
        return keyHolder.getKey() == null ? 0 : keyHolder.getKey().longValue();
    }

    @Override
    public Optional<WorkspaceJob> findJob(long projectId, long jobId) {
        String sql = "SELECT " + JOB_COLUMNS + " FROM workspace_job WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "workspace_job");
        List<WorkspaceJob> rows = jdbc.query(sql, (rs, i) -> mapJob(rs), projectId, jobId);
        return rows.stream().findFirst();
    }

    @Override
    public List<WorkspaceJob> recentJobs(long projectId, int limit) {
        String sql = "SELECT " + JOB_COLUMNS
                + " FROM workspace_job WHERE project_id = ? ORDER BY id DESC LIMIT ?";
        TenantGuard.requireProjectScope(sql, "workspace_job");
        return jdbc.query(sql, (rs, i) -> mapJob(rs), projectId, Math.max(1, limit));
    }

    @Override
    public void markRunning(long projectId, long jobId) {
        String sql = """
                UPDATE workspace_job
                   SET status = 'RUNNING', progress = 1, started_at = CURRENT_TIMESTAMP(3)
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "workspace_job");
        jdbc.update(sql, projectId, jobId);
    }

    @Override
    public void finishJob(long projectId, long jobId, String status, String bundlePath,
                          String manifestSha256, String reportJson, String errorMessage, Long operatorId) {
        String sql = """
                UPDATE workspace_job
                   SET status = ?,
                       progress = CASE WHEN ? = 'SUCCESS' THEN 100 ELSE progress END,
                       bundle_path = COALESCE(?, bundle_path),
                       manifest_sha256 = COALESCE(?, manifest_sha256),
                       report_json = COALESCE(?, report_json),
                       error_message = ?,
                       finished_at = CURRENT_TIMESTAMP(3),
                       updated_by = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "workspace_job");
        jdbc.update(sql, status, status, bundlePath, manifestSha256, reportJson, errorMessage,
                operatorId, projectId, jobId);
    }

    @Override
    public WorkspaceSnapshot snapshot(long projectId, WorkspaceSnapshot.ProjectInfo project) {
        return new WorkspaceSnapshot(project, members(projectId), cases(projectId),
                caseSets(projectId), assets(projectId), credentials(projectId),
                environments(projectId), reports(projectId), runs(projectId), files(projectId));
    }

    private List<WorkspaceSnapshot.MemberInfo> members(long projectId) {
        String sql = """
                SELECT u.username, u.display_name, m.member_role
                  FROM project_member m
                  JOIN app_user u ON u.id = m.user_id
                 WHERE m.project_id = ?
                 ORDER BY u.username
                """;
        TenantGuard.requireProjectScope(sql, "project_member");
        return jdbc.query(sql, (rs, i) -> new WorkspaceSnapshot.MemberInfo(
                rs.getString(1), rs.getString(2), rs.getString(3)), projectId);
    }

    private List<WorkspaceSnapshot.CaseInfo> cases(long projectId) {
        String caseSql = """
                SELECT id, case_code, title, case_kind, source_type, priority, domain_tag, description
                  FROM test_case
                 WHERE project_id = ?
                 ORDER BY id
                """;
        TenantGuard.requireProjectScope(caseSql, "test_case");
        Map<Long, List<WorkspaceSnapshot.StepInfo>> steps = steps(projectId);
        List<WorkspaceSnapshot.CaseInfo> result = new ArrayList<>();
        jdbc.query(caseSql, rs -> {
            long id = rs.getLong("id");
            result.add(new WorkspaceSnapshot.CaseInfo(
                    rs.getString("case_code"), rs.getString("title"), rs.getString("case_kind"),
                    rs.getString("source_type"), rs.getString("priority"), rs.getString("domain_tag"),
                    rs.getString("description"), steps.getOrDefault(id, List.of())));
        }, projectId);
        return result;
    }

    private Map<Long, List<WorkspaceSnapshot.StepInfo>> steps(long projectId) {
        String sql = """
                SELECT s.test_case_id, s.step_no, s.action, s.expectation
                  FROM test_case_step s
                  JOIN test_case c ON c.id = s.test_case_id
                 WHERE c.project_id = ?
                 ORDER BY s.test_case_id, s.step_no
                """;
        TenantGuard.requireProjectScope(sql, "test_case_step");
        Map<Long, List<WorkspaceSnapshot.StepInfo>> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            result.computeIfAbsent(rs.getLong("test_case_id"), key -> new ArrayList<>())
                    .add(new WorkspaceSnapshot.StepInfo(
                            rs.getInt("step_no"), rs.getString("action"), rs.getString("expectation")));
        }, projectId);
        return result;
    }

    private List<WorkspaceSnapshot.CaseSetInfo> caseSets(long projectId) {
        String setSql = """
                SELECT id, set_code, name, description
                  FROM test_set
                 WHERE project_id = ?
                 ORDER BY id
                """;
        TenantGuard.requireProjectScope(setSql, "test_set");
        Map<Long, List<WorkspaceSnapshot.SetItem>> items = setItems(projectId);
        List<WorkspaceSnapshot.CaseSetInfo> result = new ArrayList<>();
        jdbc.query(setSql, rs -> {
            long id = rs.getLong("id");
            result.add(new WorkspaceSnapshot.CaseSetInfo(
                    rs.getString("set_code"), rs.getString("name"), rs.getString("description"),
                    items.getOrDefault(id, List.of())));
        }, projectId);
        return result;
    }

    private Map<Long, List<WorkspaceSnapshot.SetItem>> setItems(long projectId) {
        String sql = """
                SELECT i.test_set_id, i.seq_no, c.case_code
                  FROM test_set_item i
                  JOIN test_set s ON s.id = i.test_set_id
                  JOIN test_case c ON c.id = i.test_case_id
                 WHERE s.project_id = ?
                 ORDER BY i.test_set_id, i.seq_no
                """;
        TenantGuard.requireProjectScope(sql, "test_set_item");
        Map<Long, List<WorkspaceSnapshot.SetItem>> result = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            result.computeIfAbsent(rs.getLong("test_set_id"), key -> new ArrayList<>())
                    .add(new WorkspaceSnapshot.SetItem(rs.getString("case_code"), rs.getInt("seq_no")));
        }, projectId);
        return result;
    }

    private List<WorkspaceSnapshot.AssetInfo> assets(long projectId) {
        String deviceSql = "SELECT name, management_ip FROM device WHERE project_id = ? ORDER BY name";
        TenantGuard.requireProjectScope(deviceSql, "device");
        String linkSql = "SELECT name, link_type FROM link WHERE project_id = ? ORDER BY name";
        TenantGuard.requireProjectScope(linkSql, "link");
        List<WorkspaceSnapshot.AssetInfo> result = new ArrayList<>();
        jdbc.query(deviceSql, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                result.add(new WorkspaceSnapshot.AssetInfo(
                        rs.getString(1), "DEVICE", rs.getString(2))), projectId);
        jdbc.query(linkSql, (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                result.add(new WorkspaceSnapshot.AssetInfo(
                        rs.getString(1), "LINK", rs.getString(2))), projectId);
        return result;
    }

    private List<WorkspaceSnapshot.CredentialInfo> credentials(long projectId) {
        String sql = "SELECT name, cred_type, username FROM credential WHERE project_id = ? ORDER BY name";
        TenantGuard.requireProjectScope(sql, "credential");
        return jdbc.query(sql, (rs, i) -> new WorkspaceSnapshot.CredentialInfo(
                rs.getString(1), rs.getString(2), rs.getString(3), mask(rs.getString(3))), projectId);
    }

    private List<WorkspaceSnapshot.EnvironmentInfo> environments(long projectId) {
        String sql = """
                SELECT name, profile_type, description FROM env_profile
                 WHERE project_id = ? ORDER BY name
                """;
        TenantGuard.requireProjectScope(sql, "env_profile");
        return jdbc.query(sql, (rs, i) -> new WorkspaceSnapshot.EnvironmentInfo(
                rs.getString(1), rs.getString(2), rs.getString(3)), projectId);
    }

    private List<WorkspaceSnapshot.ReportInfo> reports(long projectId) {
        String sql = """
                SELECT id, run_id, title, state, created_at FROM test_report
                 WHERE project_id = ? ORDER BY id
                """;
        TenantGuard.requireProjectScope(sql, "test_report");
        return jdbc.query(sql, (rs, i) -> new WorkspaceSnapshot.ReportInfo(
                "RPT-" + rs.getLong("id"), rs.getString("run_id"), rs.getString("title"),
                rs.getString("state"), instant(rs.getTimestamp("created_at"))), projectId);
    }

    private List<WorkspaceSnapshot.RunInfo> runs(long projectId) {
        String sql = """
                SELECT r.run_id, COALESCE(s.set_code, '') AS set_code, r.run_status, r.started_at
                  FROM run r
                  LEFT JOIN test_set s ON s.id = r.test_set_id
                 WHERE r.project_id = ?
                 ORDER BY r.id
                """;
        TenantGuard.requireProjectScope(sql, "run");
        return jdbc.query(sql, (rs, i) -> new WorkspaceSnapshot.RunInfo(
                rs.getString("run_id"), rs.getString("set_code"), rs.getString("run_status"),
                instant(rs.getTimestamp("started_at"))), projectId);
    }

    private List<WorkspaceSnapshot.FileInfo> files(long projectId) {
        String sql = "SELECT path, content FROM robot_file WHERE project_id = ? ORDER BY path";
        TenantGuard.requireProjectScope(sql, "robot_file");
        return jdbc.query(sql, (rs, i) -> new WorkspaceSnapshot.FileInfo(
                rs.getString("path"), rs.getString("content") == null ? "" : rs.getString("content")),
                projectId);
    }

    private static WorkspaceJob mapJob(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkspaceJob(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("job_type"),
                rs.getString("granularity"), rs.getString("target_path"), rs.getString("status"),
                rs.getInt("progress"), rs.getString("bundle_path"), rs.getString("strategy"),
                rs.getString("manifest_sha256"), rs.getString("report_json"),
                rs.getString("error_message"), point(rs.getTimestamp("started_at")),
                point(rs.getTimestamp("finished_at")), point(rs.getTimestamp("created_at")));
    }

    private static String instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static java.time.Instant point(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String mask(String username) {
        if (username == null || username.isBlank()) {
            return "***";
        }
        return "***" + (username.length() <= 2 ? username : username.substring(username.length() - 2));
    }

    private static boolean isExport(String jobType) {
        return "EXPORT".equalsIgnoreCase(jobType);
    }
}
