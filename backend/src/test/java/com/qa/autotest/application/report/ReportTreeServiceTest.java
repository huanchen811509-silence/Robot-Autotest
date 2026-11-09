package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ReportReadRepository;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.report.CaseLogRef;
import com.qa.autotest.domain.report.CaseVerdict;
import com.qa.autotest.domain.report.KeywordRow;
import com.qa.autotest.domain.report.RunCaseRow;
import com.qa.autotest.domain.report.RunSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 套件树透出命令三列（FR-v3.9-03 ①）。
 *
 * <p>这里刻意只测「仓储给出什么、树就带出什么」这一段——入库提取由
 * {@link CaseCommandExtractorTest} 覆盖，导出由 {@code DocxExporterTest} 覆盖。
 * 中间这段最容易掉字段：加列时忘了在 {@code loadTree} 里传下去，SQL 有值、接口没有，
 * 前端就永远显示「未采集」。
 */
class ReportTreeServiceTest {

    private static final long PROJECT = 7L;
    private static final String RUN_ID = "RUN-CMD-001";

    @Test
    @DisplayName("命令三列原样带出到 TreeCase（有命令 / NONE / 历史 null 三种）")
    void carriesCommandColumns() {
        List<RunCaseRow> rows = List.of(
                new RunCaseRow("suites/a.robot.case-cmd", "PASS", 12L, null,
                        "ipmitool raw 0x30 0x93", "RF_KEYWORD", 0),
                new RunCaseRow("suites/a.robot.case-none", "PASS", 8L, null,
                        "NONE", "NONE", null),
                new RunCaseRow("suites/a.robot.case-legacy", "FAIL", 3L, "boom"));

        ReportTreeService service = new ReportTreeService(repository(rows), passAccess());
        ReportTreeService.TreeSuite root = service.tree(PROJECT, RUN_ID);

        List<ReportTreeService.TreeCase> cases = collect(root);
        assertEquals(3, cases.size());

        ReportTreeService.TreeCase cmd = byKey(cases, "suites/a.robot.case-cmd");
        assertEquals("ipmitool raw 0x30 0x93", cmd.commandText());
        assertEquals("RF_KEYWORD", cmd.commandSource());
        assertEquals(0, cmd.commandExitCode());

        ReportTreeService.TreeCase none = byKey(cases, "suites/a.robot.case-none");
        assertEquals("NONE", none.commandText(), "无命令必须是显式 NONE，不能是空串");
        assertNull(none.commandExitCode());

        ReportTreeService.TreeCase legacy = byKey(cases, "suites/a.robot.case-legacy");
        assertNull(legacy.commandText(), "历史行保持 null，前端据此显示「未采集」");
        assertNull(legacy.commandSource());
    }

    @Test
    @DisplayName("FAIL 用例同时带命令、退出码与失败消息（报告页要并排看）")
    void failedCaseKeepsCommandAndFailure() {
        List<RunCaseRow> rows = List.of(
                new RunCaseRow("suites/a.robot.case-fail", "FAIL", 900L,
                        "expected 190 got 120", "iperf3 -c 192.0.2.10 -t 30", "STDOUT_EXTRACT", 1));

        ReportTreeService service = new ReportTreeService(repository(rows), passAccess());
        ReportTreeService.TreeSuite root = service.tree(PROJECT, RUN_ID);

        List<ReportTreeService.TreeCase> cases = collect(root);
        assertEquals(1, cases.size());
        ReportTreeService.TreeCase test = cases.get(0);
        assertEquals("FAIL", test.verdict());
        assertEquals("iperf3 -c 192.0.2.10 -t 30", test.commandText());
        assertEquals(1, test.commandExitCode());
        assertEquals("expected 190 got 120", test.failureMsg());
    }

    private static ReportTreeService.TreeCase byKey(List<ReportTreeService.TreeCase> cases,
                                                   String caseKey) {
        return cases.stream().filter(c -> c.caseKey().equals(caseKey)).findFirst().orElseThrow();
    }

    /** 递归收集所有用例（树是按目录分层的）。 */
    private static List<ReportTreeService.TreeCase> collect(ReportTreeService.TreeSuite suite) {
        List<ReportTreeService.TreeCase> out = new java.util.ArrayList<>(suite.cases());
        for (ReportTreeService.TreeSuite child : suite.suites()) {
            out.addAll(collect(child));
        }
        return out;
    }

    private static ReportReadRepository repository(List<RunCaseRow> rows) {
        return new ReportReadRepository() {
            @Override
            public Optional<RunSnapshot> findRun(long projectId, String runId) {
                return Optional.of(new RunSnapshot(projectId, runId, "finished",
                        "GPU-01", "GPU-01 mlx5_0 -> SW-01", Instant.parse("2026-11-09T02:00:00Z")));
            }

            @Override
            public Optional<RunSnapshot> findBaseline(long projectId, String dut, String link,
                                                      Instant before, String excludeRunId) {
                return Optional.empty();
            }

            @Override
            public List<RunSnapshot> listBaselineCandidates(long projectId, String dut, String link,
                                                            String excludeRunId, int limit) {
                return List.of();
            }

            @Override
            public List<CaseVerdict> listCaseVerdicts(long projectId, String runId) {
                return List.of();
            }

            @Override
            public List<RunCaseRow> listCases(long projectId, String runId) {
                assertTrue(projectId == PROJECT && RUN_ID.equals(runId), "查询必须带项目与 RUN_ID");
                return rows;
            }

            @Override
            public List<KeywordRow> listKeywords(long projectId, String runId) {
                return List.of();
            }

            @Override
            public Optional<CaseLogRef> findCaseLog(long projectId, String runId, String caseKey) {
                return Optional.empty();
            }
        };
    }

    private static AccessControlService passAccess() {
        return new AccessControlService(null, null) {
            @Override
            public void bindProject(long projectId) {
                // 单测没有认证上下文，TenantContext.setProjectId 会抛「未认证」。
                // 这里只验证树重建，租户绑定由 TenantGuard 的边界测试覆盖。
            }

            @Override
            public void require(PermissionResource resource, PermissionAction action) {
                // 权限矩阵由 AuthorizationBoundaryTest 覆盖
            }
        };
    }
}
