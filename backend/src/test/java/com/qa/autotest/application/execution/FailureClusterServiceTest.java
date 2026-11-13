package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.FailureResultRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.execution.FailureClass;
import com.qa.autotest.domain.execution.FailureClusterKey;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 失败聚类（ORCH-03）：归一化、类+簇两级、稳定排序、空字段兜底、确定性。
 *
 * <p>刻意不测「分类规则本身」——那是 `FailureClassifier` 的职责，已有其他用例覆盖。
 * 本类只保证两件事：**喂进去的文本被正确归一**，以及**同一输入两次输出一模一样**。
 */
class FailureClusterServiceTest {

    private static final long PROJECT = 7L;
    private static final String RUN_ID = "RUN-20261106-001";

    private FakeResults results;
    private InMemoryRunRepository runs;
    private StubCaseRuns caseRuns;
    private FailureClusterService service;

    @BeforeEach
    void setUp() {
        results = new FakeResults();
        runs = new InMemoryRunRepository();
        caseRuns = new StubCaseRuns();
        service = new FailureClusterService(results,
                new FailureClassifier(null), new PermissiveAccess(), caseRuns, runs);
    }

    @Test
    @DisplayName("路径与行号不同的两条失败归到同一簇")
    void 路径归一() {
        results.add(line("/a/b/x.py:12", "AssertionError: boom"));
        results.add(line("/c/d/y.py:98", "AssertionError: boom"));

        FailureClusterView view = service.cluster(PROJECT, RUN_ID);

        assertEquals(1, view.classes().size());
        assertEquals(1, view.classes().get(0).clusters().size());
        assertEquals(2, view.classes().get(0).clusters().get(0).count());
    }

    @Test
    @DisplayName("数字不同的断言失败归到同一簇（3 != 5 与 3 != 7）")
    void 数字归一() {
        results.add(line(null, "AssertionError: 3 != 5"));
        results.add(line(null, "AssertionError: 3 != 7"));

        FailureClusterView view = service.cluster(PROJECT, RUN_ID);

        assertEquals(1, view.classes().get(0).clusters().size());
        assertEquals("AssertionError: <n> != <n>", view.classes().get(0).clusters().get(0).key());
    }

    @Test
    @DisplayName("多行堆栈只看首行：同一失败不会因为第 2 行不同被拆成两簇")
    void 只看首行() {
        results.add(line(null, "Traceback: boom\n  at /a/b.py:1\n  at /a/c.py:2"));
        results.add(line(null, "Traceback: boom\n  at /x/y.py:9"));

        FailureClusterView view = service.cluster(PROJECT, RUN_ID);

        assertEquals(1, view.classes().get(0).clusters().size());
    }

    @Test
    @DisplayName("空失败信息归入固定簇「未分类」，不丢数据")
    void 空字段兜底() {
        results.add(line(null, null));
        results.add(line(null, "   "));

        FailureClusterView view = service.cluster(PROJECT, RUN_ID);

        assertEquals(2, view.total());
        assertEquals(FailureClusterKey.UNCLASSIFIED,
                view.classes().get(0).clusters().get(0).key());
    }

    @Test
    @DisplayName("FR-v5.6-22：展示名取首个样本首行原文，簇指纹保持归一化结果")
    void 展示名与指纹分离() {
        results.add(line("TC-1", "AssertionError: PFC 使能优先级期望 3 实际 5\n  at /a/b.py:12"));
        results.add(line("TC-2", "AssertionError: PFC 使能优先级期望 3 实际 7"));
        results.add(line("TC-3", "AssertionError: PFC 使能优先级期望 3 实际 9"));

        FailureClusterView.Cluster cluster = service.cluster(PROJECT, RUN_ID)
                .classes().get(0).clusters().get(0);

        // 指纹抹掉了数字差异，展示名保留第一个样本的原文（含真实片段与行号前的首行）
        assertEquals(3, cluster.count());
        assertNotEquals(cluster.display(), cluster.key());
        assertTrue(cluster.display().contains("PFC 使能优先级"), "展示名丢了真实片段");
        assertTrue(cluster.display().contains("实际 5"), "展示名没取首个样本");
        assertFalse(cluster.display().contains("\n"), "展示名不该带多行堆栈");
        assertFalse(cluster.display().contains("<n>"), "展示名被当成指纹归一了");
    }

    @Test
    @DisplayName("FR-v5.6-22：失败消息为空时展示名回落到簇指纹，界面不出现空白标题")
    void 展示名空值回落() {
        results.add(line("TC-1", null));

        FailureClusterView.Cluster cluster = service.cluster(PROJECT, RUN_ID)
                .classes().get(0).clusters().get(0);

        assertEquals(FailureClusterKey.UNCLASSIFIED, cluster.key());
        assertEquals(cluster.key(), cluster.display());
    }

    @Test
    @DisplayName("两级结构：环境类与断言类分开，不混在一张表里")
    void 类加簇两级() {
        // ENV 规则默认含 "connection refused" 一类关键词，用它把一条打到 ENV
        results.add(line(null, "Connection refused to 10.0.0.1:22"));
        results.add(line(null, "AssertionError: 3 != 5"));

        FailureClusterView view = service.cluster(PROJECT, RUN_ID);

        List<String> classes = view.classes().stream().map(FailureClusterView.ClassGroup::failureClass)
                .toList();
        assertTrue(classes.size() >= 2, "至少应分成两类，实际：" + classes);
        assertTrue(classes.contains(FailureClass.ASSERT.name()));
    }

    @Test
    @DisplayName("簇按条数倒序，同条数按簇键字典序（不依赖插入顺序）")
    void 稳定排序() {
        results.add(line(null, "B failure"));
        results.add(line(null, "A failure"));
        results.add(line(null, "A failure"));
        results.add(line(null, "C failure"));

        FailureClusterView view = service.cluster(PROJECT, RUN_ID);
        List<String> keys = view.classes().get(0).clusters().stream()
                .map(FailureClusterView.Cluster::key).toList();

        assertEquals(List.of("A failure", "B failure", "C failure"), keys);
    }

    @Test
    @DisplayName("同一输入两次聚类的输出逐字段一致（确定性）")
    void 两次结果一致() {
        for (int i = 0; i < 50; i++) {
            results.add(line("/x/y" + i + ".py:" + i, "AssertionError: " + i + " != 99"));
        }

        FailureClusterView first = service.cluster(PROJECT, RUN_ID);
        FailureClusterView second = service.cluster(PROJECT, RUN_ID);

        assertEquals(first.total(), second.total());
        assertEquals(first.classes().size(), second.classes().size());
        for (int i = 0; i < first.classes().size(); i++) {
            List<String> left = first.classes().get(i).clusters().stream()
                    .map(FailureClusterView.Cluster::key).toList();
            List<String> right = second.classes().get(i).clusters().stream()
                    .map(FailureClusterView.Cluster::key).toList();
            assertEquals(left, right);
        }
    }

    @Test
    @DisplayName("样例最多 3 条，但 caseIds 不截断（重跑要用全量）")
    void 样例截断而清单不截断() {
        for (int i = 1; i <= 5; i++) {
            results.add(new FailureResultRepository.FailureRow(i, (long) i,
                    "CASE-" + i, "title-" + i, "FAIL", "AssertionError: boom"));
        }

        FailureClusterView.Cluster cluster = service.cluster(PROJECT, RUN_ID)
                .classes().get(0).clusters().get(0);

        assertEquals(3, cluster.samples().size());
        assertEquals(5, cluster.caseIds().size());
    }

    @Test
    @DisplayName("占比：类别占全部失败、簇占全部失败，各自按同一分母算")
    void 占比() {
        results.add(line(null, "boom"));
        results.add(line(null, "boom"));
        results.add(line(null, "other"));

        FailureClusterView view = service.cluster(PROJECT, RUN_ID);

        // 三条都是 ASSERT 类：类别占比 3/3 = 1.0
        assertEquals(1.0, view.classes().get(0).ratio(), 0.00005);
        // 簇占比按全部失败算：2/3 = 0.6667（不是占本类的 2/3 —— 这里恰好一样，故意留一条
        // 不同类的用例就会分叉，所以分母口径必须写死在实现里）
        assertEquals(0.6667, view.classes().get(0).clusters().get(0).ratio(), 0.00005);
    }

    @Test
    @DisplayName("空 runId 直接拒绝，不返回空视图糊过去")
    void 空runId拒绝() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.cluster(PROJECT, " "));
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
    }

    @Test
    @DisplayName("簇不存在时重跑返回 404")
    void 重跑簇不存在() {
        results.add(line(null, "boom"));
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.rerun(PROJECT, RUN_ID, "no-such-cluster"));
        assertEquals(ErrorCode.NOT_FOUND, error.errorCode());
    }

    @Test
    @DisplayName("归一化：IP 与端口被打成占位符，不同环境不分成两簇")
    void IP归一() {
        results.add(line(null, "connect to 10.0.0.1:22 failed"));
        results.add(line(null, "connect to 192.168.1.9:2200 failed"));

        FailureClusterView view = service.cluster(PROJECT, RUN_ID);

        assertEquals(1, view.classes().get(0).clusters().size());
    }

    @Test
    @DisplayName("N8：簇重跑回填 attempt_no（原运行 +1）与 retry_of_run_id")
    void 重跑回填重试来源() {
        results.add(line("CASE-1", "Connection refused"));

        RunRecord source = run(1L, RUN_ID, 1);
        runs.seed(source);
        caseRuns.next = run(2L, "RUN-NEXT", 1);
        runs.seed(caseRuns.next);

        RunRecord created = service.rerun(PROJECT, RUN_ID, "Connection refused");

        assertEquals("RUN-NEXT", created.runId());
        assertEquals(2, created.attemptNo(), "原 attempt_no=1，重跑应为 2");
        assertEquals(RUN_ID, runs.retryOf("RUN-NEXT").orElseThrow());
    }

    @Test
    @DisplayName("N8：原运行 attempt_no 为 0（历史数据）时按 1 处理，重跑记 2")
    void 重跑兼容零attempt() {
        results.add(line("CASE-2", "boom"));
        runs.seed(run(1L, RUN_ID, 0));
        caseRuns.next = run(2L, "RUN-NEXT-2", 0);
        runs.seed(caseRuns.next);

        RunRecord created = service.rerun(PROJECT, RUN_ID, "boom");

        assertEquals(2, created.attemptNo());
        assertEquals(RUN_ID, runs.retryOf("RUN-NEXT-2").orElseThrow());
    }

    @Test
    @DisplayName("跨 run：最近 2 轮里同归一化簇合并计数，作用域标记为 recent:N")
    void 跨run合并() {
        runs.seed(run(1L, "RUN-A", 1));
        runs.seed(run(2L, "RUN-B", 1));
        results.add("RUN-A", line("CASE-1", "AssertionError: 3 != 5"));
        results.add("RUN-B", line("CASE-2", "AssertionError: 3 != 7"));
        results.add("RUN-B", line("CASE-3", "Connection refused to 10.0.0.1:22"));

        FailureClusterView view = service.clusterRecent(PROJECT, 2);

        assertEquals("recent:2", view.runId());
        assertEquals(3, view.total());
        FailureClusterView.Cluster merged = view.classes().stream()
                .flatMap(group -> group.clusters().stream())
                .filter(cluster -> cluster.key().equals("AssertionError: <n> != <n>"))
                .findFirst().orElseThrow();
        assertEquals(2, merged.count(), "两轮各一条，合并后应为 2");
    }

    @Test
    @DisplayName("跨 run：runs 越界（0 / 21）在入口拒绝")
    void 跨run越界拒绝() {
        for (int bad : new int[]{0, 21}) {
            BusinessException error = assertThrows(BusinessException.class,
                    () -> service.clusterRecent(PROJECT, bad));
            assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
        }
    }

    @Test
    @DisplayName("跨 run：没有任何运行时返回空视图，不抛错")
    void 跨run空数据() {
        FailureClusterView view = service.clusterRecent(PROJECT, 5);

        assertEquals("recent:5", view.runId());
        assertEquals(0, view.total());
        assertTrue(view.classes().isEmpty());
    }

    private static RunRecord run(long id, String runId, int attemptNo) {
        return new RunRecord(id, runId, PROJECT, null, RunState.FAILED, 0, 1, attemptNo,
                3, 1, 2, "assert", "reason", "message", null, null, null, null, null, null);
    }

    /** CaseRunService 桩：只回一个预置运行记录，不真的起执行。 */
    static final class StubCaseRuns extends CaseRunService {

        RunRecord next;

        StubCaseRuns() {
            super(null, null, null, null);
        }

        @Override
        public RunRecord run(CaseRunCommand command) {
            return next;
        }
    }


    private static FailureResultRepository.FailureRow line(String caseCode, String failureMsg) {
        return new FailureResultRepository.FailureRow(
                System.nanoTime(), caseCode == null ? null : (long) caseCode.hashCode(),
                caseCode, "title", "FAIL", failureMsg);
    }

    /** 内存失败行仓储：默认行用于单轮用例，按 runId 登记的行用于跨轮用例。 */
    static final class FakeResults implements FailureResultRepository {

        private final List<FailureRow> rows = new ArrayList<>();
        private final java.util.Map<String, List<FailureRow>> byRun = new java.util.HashMap<>();

        void add(FailureRow row) {
            rows.add(row);
        }

        void add(String runId, FailureRow row) {
            byRun.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(row);
        }

        @Override
        public List<FailureRow> failedRows(long projectId, String runId) {
            List<FailureRow> scoped = byRun.get(runId);
            return scoped != null ? List.copyOf(scoped) : List.copyOf(rows);
        }

        @Override
        public RunFailureContext runContext(long projectId, String runId) {
            return new RunFailureContext(runId, "FAILED", "assert", "ASSERT");
        }
    }

    /** 权限桩：不做项目/角色校验。 */
    static final class PermissiveAccess extends AccessControlService {

        PermissiveAccess() {
            super(null, null);
        }

        @Override
        public void bindProject(long projectId) {
            // 测试不做项目成员校验
        }

        @Override
        public void require(PermissionResource resource, PermissionAction action) {
            // 测试不做角色校验
        }
    }
}
