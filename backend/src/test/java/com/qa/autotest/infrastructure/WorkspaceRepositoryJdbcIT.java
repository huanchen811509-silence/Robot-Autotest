package com.qa.autotest.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.workspace.WorkspaceJob;
import com.qa.autotest.domain.workspace.WorkspaceSnapshot;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 工作区仓储真库 IT（FR-v4.1-01/02 ⑤）。
 *
 * <p><b>为什么必须连真库</b>：这层几乎全是 SQL——快照九类查询的 JOIN/排序、
 * {@code workspace_job} 的 active_lock 唯一键、finishJob 的 COALESCE 回写。
 * 用替身只能证明「方法被调到」，证明不了列序、租户条件和并发锁。</p>
 *
 * <p>与既有 IT 一致：库名必须以 {@code _it} 结尾（ADR-0002，不用 H2）。</p>
 */
@Tag("it")
class WorkspaceRepositoryJdbcIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");

    @Test
    @DisplayName("快照取全九类、任务状态流转、同项目活动锁冲突、跨项目查不到")
    void repositoryAgainstRealDatabase() {
        assertThat(DB_NAME).endsWith("_it");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        WorkspaceRepositoryJdbc repo = new WorkspaceRepositoryJdbc(jdbc);

        String stamp = String.valueOf(System.currentTimeMillis());
        String code = "IT-WS-" + stamp;
        Long projectId = null;
        Long otherProjectId = null;
        try {
            projectId = seedProject(jdbc, code);
            otherProjectId = seedProject(jdbc, code + "-OTHER");
            Long userId = firstUserId(jdbc);
            jdbc.update("INSERT INTO project_member (project_id, user_id, member_role) VALUES (?, ?, 'admin')",
                    projectId, userId);
            seedCase(jdbc, projectId);
            seedSet(jdbc, projectId);
            seedAsset(jdbc, projectId);
            seedCredential(jdbc, projectId);
            seedEnvironment(jdbc, projectId);
            seedRun(jdbc, projectId, "RUN-WS-" + stamp);
            seedReport(jdbc, projectId, "RUN-WS-" + stamp);
            seedFile(jdbc, projectId, "hello.robot", "*** Settings ***\n");

            WorkspaceSnapshot.ProjectInfo info =
                    new WorkspaceSnapshot.ProjectInfo(code, "工作区 IT", "", "robot");
            WorkspaceSnapshot snapshot = repo.snapshot(projectId, info);
            assertThat(snapshot.project()).isEqualTo(info);
            assertThat(snapshot.members()).singleElement()
                    .satisfies(m -> assertThat(m.role()).isEqualTo("admin"));
            assertThat(snapshot.cases()).hasSize(1);
            assertThat(snapshot.cases().get(0).steps()).singleElement()
                    .satisfies(s -> assertThat(s.action()).isEqualTo("打开首页"));
            assertThat(snapshot.caseSets()).singleElement()
                    .satisfies(s -> assertThat(s.items()).singleElement()
                            .satisfies(i -> assertThat(i.seqNo()).isEqualTo(1)));
            assertThat(snapshot.assets()).hasSize(2); // device + link
            assertThat(snapshot.credentials()).singleElement()
                    .satisfies(c -> assertThat(c.mask()).isEqualTo("***ot"));
            assertThat(snapshot.environments()).singleElement()
                    .satisfies(e -> assertThat(e.profileType()).isEqualTo("test"));
            assertThat(snapshot.reports()).singleElement()
                    .satisfies(r -> assertThat(r.runId()).isEqualTo("RUN-WS-" + stamp));
            assertThat(snapshot.runs()).singleElement()
                    .satisfies(r -> assertThat(r.testSetCode()).isEqualTo("TS-WS-" + stamp));
            assertThat(snapshot.files()).singleElement()
                    .satisfies(f -> assertThat(f.content()).contains("*** Settings ***"));

            long jobId = repo.createJob(projectId, "EXPORT", "PROJECT", "", "SKIP", null, userId);
            WorkspaceJob pending = repo.findJob(projectId, jobId).orElseThrow();
            assertThat(pending.status()).isEqualTo("PENDING");
            assertThat(pending.granularity()).isEqualTo("PROJECT");

            repo.markRunning(projectId, jobId);
            WorkspaceJob running = repo.findJob(projectId, jobId).orElseThrow();
            assertThat(running.status()).isEqualTo("RUNNING");
            assertThat(running.startedAt()).isNotNull();

            // 活动锁：未完成时同项目再建同类任务 → 409
            final Long pid = projectId;
            assertThatThrownBy(() -> repo.createJob(pid, "EXPORT", "PROJECT", "", "SKIP",
                    null, userId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).errorCode())
                            .isEqualTo(ErrorCode.CONFLICT));

            repo.finishJob(projectId, jobId, "SUCCESS", code, "a".repeat(64), "{}", null, userId);
            WorkspaceJob done = repo.findJob(projectId, jobId).orElseThrow();
            assertThat(done.status()).isEqualTo("SUCCESS");
            assertThat(done.progress()).isEqualTo(100);
            assertThat(done.bundlePath()).isEqualTo(code);
            assertThat(done.manifestSha256()).hasSize(64);
            assertThat(done.finishedAt()).isNotNull();

            // 完成后锁释放，可再次创建
            long next = repo.createJob(projectId, "EXPORT", "PROJECT", "", "SKIP", null, userId);
            assertThat(next).isGreaterThan(jobId);
            repo.finishJob(projectId, next, "FAILED", null, null, null, "代码 500", userId);

            List<WorkspaceJob> recent = repo.recentJobs(projectId, 10);
            assertThat(recent).extracting(WorkspaceJob::id).containsExactly(next, jobId);
            assertThat(recent.get(0).errorMessage()).isEqualTo("代码 500");
            assertThat(repo.findJob(otherProjectId, jobId)).as("跨项目必须查不到").isEmpty();
            assertThat(repo.recentJobs(otherProjectId, 10)).isEmpty();
        } finally {
            cleanup(jdbc, projectId, otherProjectId);
        }
    }

    private DataSource dataSource() {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String user = System.getenv().getOrDefault("DB_USERNAME", "robot");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME
                + "?useUnicode=true&characterEncoding=utf8";
        return new DriverManagerDataSource(url, user, password);
    }

    private static Long seedProject(JdbcTemplate jdbc, String code) {
        jdbc.update("INSERT INTO project (code, name, status) VALUES (?, ?, 1)", code, "工作区 IT");
        return jdbc.queryForObject("SELECT id FROM project WHERE code = ?", Long.class, code);
    }

    private static Long firstUserId(JdbcTemplate jdbc) {
        Long id = jdbc.queryForObject("SELECT MIN(id) FROM app_user", Long.class);
        if (id == null) {
            jdbc.update("INSERT INTO app_user (username, display_name, password_hash, status) "
                    + "VALUES (?, ?, 'it', 1)", "it-ws-user", "工作区 IT");
            id = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'it-ws-user'",
                    Long.class);
        }
        return id;
    }

    private static void seedCase(JdbcTemplate jdbc, Long projectId) {
        String stamp = String.valueOf(projectId);
        jdbc.update("INSERT INTO test_case (project_id, case_code, title, case_kind, source_type, "
                + "priority, safety_level, domain_tag, status, description) "
                + "VALUES (?, ?, '登录', 'ui', 'manual', 'P1', 'LX', 'auth', 1, '')",
                projectId, "TC-WS-" + stamp);
        Long caseId = jdbc.queryForObject(
                "SELECT id FROM test_case WHERE project_id = ? AND case_code = ?", Long.class,
                projectId, "TC-WS-" + stamp);
        jdbc.update("INSERT INTO test_case_step (test_case_id, step_no, action, expectation) "
                + "VALUES (?, 1, '打开首页', '看到登录框')", caseId);
    }

    private static void seedSet(JdbcTemplate jdbc, Long projectId) {
        String stamp = String.valueOf(projectId);
        jdbc.update("INSERT INTO test_set (project_id, set_code, name, description) "
                + "VALUES (?, ?, '冒烟', '')", projectId, "TS-WS-" + stamp);
        Long setId = jdbc.queryForObject(
                "SELECT id FROM test_set WHERE project_id = ? AND set_code = ?", Long.class,
                projectId, "TS-WS-" + stamp);
        Long caseId = jdbc.queryForObject("SELECT id FROM test_case WHERE project_id = ?",
                Long.class, projectId);
        jdbc.update("INSERT INTO test_set_item (test_set_id, test_case_id, seq_no) VALUES (?, ?, 1)",
                setId, caseId);
    }

    private static void seedAsset(JdbcTemplate jdbc, Long projectId) {
        jdbc.update("INSERT INTO device (project_id, name, device_type, management_ip, status) "
                + "VALUES (?, 'dev-ws', 'SERVER', '10.9.9.9', 1)", projectId);
        jdbc.update("INSERT INTO link (project_id, name, link_type, status) "
                + "VALUES (?, 'link-ws', 'L2', 1)", projectId);
    }

    private static void seedCredential(JdbcTemplate jdbc, Long projectId) {
        jdbc.update("INSERT INTO credential (project_id, name, cred_type, username, secret_ref) "
                + "VALUES (?, 'cred-ws', 'SSH', 'root', 'it/cred-ws')", projectId);
    }

    private static void seedEnvironment(JdbcTemplate jdbc, Long projectId) {
        jdbc.update("INSERT INTO env_profile (project_id, name, profile_type, description) "
                + "VALUES (?, 'env-ws', 'test', '')", projectId);
    }

    private static void seedRun(JdbcTemplate jdbc, Long projectId, String runId) {
        Long setId = jdbc.queryForObject("SELECT id FROM test_set WHERE project_id = ?",
                Long.class, projectId);
        jdbc.update("INSERT INTO run (run_id, project_id, test_set_id, run_status, enqueued_at, "
                + "started_at, finished_at, total_tests, passed_tests, failed_tests, trigger_type) "
                + "VALUES (?, ?, ?, 'finished', NOW(3), NOW(3), NOW(3), 1, 1, 0, 'manual')",
                runId, projectId, setId);
    }

    private static void seedReport(JdbcTemplate jdbc, Long projectId, String runId) {
        jdbc.update("INSERT INTO test_report (project_id, run_id, title, state) "
                + "VALUES (?, ?, '报告', 'draft')", projectId, runId);
    }

    private static void seedFile(JdbcTemplate jdbc, Long projectId, String path, String content) {
        jdbc.update("INSERT INTO robot_file (project_id, path, content, content_sha256, current_version) "
                + "VALUES (?, ?, ?, SHA2(?, 256), 1)", projectId, path, content, content);
    }

    private static void cleanup(JdbcTemplate jdbc, Long projectId, Long otherProjectId) {
        if (projectId != null) {
            try {
                jdbc.update("DELETE FROM workspace_job WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM test_report WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM run WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM test_set_item WHERE test_set_id IN "
                        + "(SELECT id FROM test_set WHERE project_id = ?)", projectId);
                jdbc.update("DELETE FROM test_set WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM test_case_step WHERE test_case_id IN "
                        + "(SELECT id FROM test_case WHERE project_id = ?)", projectId);
                jdbc.update("DELETE FROM test_case WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM device WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM link WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM credential WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM env_profile WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM robot_file WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project_member WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project WHERE id = ?", projectId);
            } catch (RuntimeException ex) {
                System.err.println("[WorkspaceRepositoryJdbcIT] 清理失败：" + ex.getMessage());
            }
        }
        if (otherProjectId != null) {
            jdbc.update("DELETE FROM project WHERE id = ?", otherProjectId);
        }
    }
}
