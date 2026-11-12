package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.ManualExecutionRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.execution.ManualExecution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手工执行（FR-v5.4-04 / R5–R6 / W54-04）。
 *
 * <p>核心判据：<b>未确认的步骤不能提交结论</b>。这条要是松了，手工执行就退化成
 * 「填一个 PASS 就完事」，步骤表存在与否毫无差别——而那正是这个功能唯一的实质。
 */
class ManualExecutionServiceTest {

    private static final long PROJECT = 3L;
    private static final long RUN = 100L;
    private static final long CASE = 200L;

    private FakeRepository repo;
    private ManualExecutionService service;

    @BeforeEach
    void setUp() {
        repo = new FakeRepository();
        service = new ManualExecutionService(repo);
    }

    @Test
    void 开始执行会为每一步建未确认占位() {
        ManualExecution execution = service.start(PROJECT, RUN, CASE, List.of(1, 2, 3),
                7L, "张三", "借用 lab-03");
        assertEquals(3, execution.steps().size());
        assertTrue(execution.steps().stream().noneMatch(ManualExecution.StepResult::confirmed));
        assertEquals(List.of(1, 2, 3), execution.unconfirmedSteps());
        assertFalse(execution.submitted());
        assertEquals("张三", execution.executorName(), "执行人姓名要存快照");
    }

    @Test
    void 没有步骤的用例不能发起手工执行() {
        // 没有步骤就无从逐步确认，这样的「手工执行」只剩一个结论，等于没有证据
        assertThrows(BusinessException.class,
                () -> service.start(PROJECT, RUN, CASE, List.of(), 7L, "张三", null));
        assertThrows(BusinessException.class,
                () -> service.start(PROJECT, RUN, CASE, null, 7L, "张三", null));
    }

    @Test
    void 确认一步后该步不再出现在未确认列表() {
        ManualExecution execution = service.start(PROJECT, RUN, CASE, List.of(1, 2),
                7L, "张三", null);
        service.confirm(PROJECT, execution.id(), 1, "PASS", "指示灯正常",
                List.of("evidence/step1.png"));

        ManualExecution after = service.find(PROJECT, execution.id());
        assertEquals(List.of(2), after.unconfirmedSteps());
        ManualExecution.StepResult step1 = after.steps().get(0);
        assertTrue(step1.confirmed());
        assertEquals("指示灯正常", step1.note());
        assertEquals(1, step1.attachments().size());
    }

    @Test
    void 未确认全部步骤时提交被拒并列出步骤号() {
        ManualExecution execution = service.start(PROJECT, RUN, CASE, List.of(1, 2, 3),
                7L, "张三", null);
        service.confirm(PROJECT, execution.id(), 1, "PASS", null, List.of());

        ManualExecutionService.SubmitResult result =
                service.submit(PROJECT, execution.id(), "PASS", "看起来没问题");
        assertFalse(result.submitted());
        assertEquals(List.of(2, 3), result.unconfirmedSteps());
        assertTrue(result.reason().contains("2, 3"), result.reason());
        assertNull(service.find(PROJECT, execution.id()).verdict(),
                "被拒时不能写结论——半提交的记录比没提交更糟");
    }

    @Test
    void 全部确认后可以提交() {
        ManualExecution execution = service.start(PROJECT, RUN, CASE, List.of(1, 2),
                7L, "张三", null);
        service.confirm(PROJECT, execution.id(), 1, "PASS", null, List.of());
        service.confirm(PROJECT, execution.id(), 2, "PASS", null, List.of());

        ManualExecutionService.SubmitResult result =
                service.submit(PROJECT, execution.id(), "PASS", "两步都正常");
        assertTrue(result.submitted());
        assertNotNull(result.execution().finishedAt());
        assertEquals("PASS", result.execution().verdict());
    }

    @Test
    void 已提交后不能再改步骤也不能重复提交() {
        ManualExecution execution = service.start(PROJECT, RUN, CASE, List.of(1),
                7L, "张三", null);
        service.confirm(PROJECT, execution.id(), 1, "FAIL", "风扇不转", List.of());
        service.submit(PROJECT, execution.id(), "FAIL", "硬件问题");

        assertThrows(BusinessException.class,
                () -> service.confirm(PROJECT, execution.id(), 1, "PASS", "改主意", List.of()));
        assertThrows(BusinessException.class,
                () -> service.submit(PROJECT, execution.id(), "PASS", "再提交一次"));
    }

    @Test
    void 结论必须是四个允许值之一() {
        ManualExecution execution = service.start(PROJECT, RUN, CASE, List.of(1),
                7L, "张三", null);
        service.confirm(PROJECT, execution.id(), 1, "PASS", null, List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.submit(PROJECT, execution.id(), "OK", null));
        assertTrue(error.getMessage().contains("BLOCKED"), error.getMessage());
    }

    @Test
    void 单步结论也要校验() {
        ManualExecution execution = service.start(PROJECT, RUN, CASE, List.of(1),
                7L, "张三", null);
        assertThrows(BusinessException.class,
                () -> service.confirm(PROJECT, execution.id(), 1, "MAYBE", null, List.of()));
        assertThrows(BusinessException.class,
                () -> service.confirm(PROJECT, execution.id(), 1, null, null, List.of()));
    }

    @Test
    void 不能确认用例定义里不存在的步骤() {
        ManualExecution execution = service.start(PROJECT, RUN, CASE, List.of(1, 2),
                7L, "张三", null);
        // 允许临时加步骤等于允许绕过用例定义，记录就和台账对不上了
        assertThrows(BusinessException.class,
                () -> service.confirm(PROJECT, execution.id(), 9, "PASS", null, List.of()));
    }

    @Test
    void BLOCKED_是合法结论() {
        ManualExecution execution = service.start(PROJECT, RUN, CASE, List.of(1),
                7L, "张三", null);
        service.confirm(PROJECT, execution.id(), 1, "SKIP", "缺夹具", List.of());
        ManualExecutionService.SubmitResult result =
                service.submit(PROJECT, execution.id(), "BLOCKED", "设备被占用，明天再验");
        assertTrue(result.submitted(), "环境不具备是客观事实，要能如实记录而不冒充 PASS 或 FAIL");
    }

    @Test
    void 附件路径序列化与解析往返一致() {
        String json = ManualExecutionService.attachmentsJson(
                List.of("evidence/a.png", " evidence/b.log "));
        assertEquals(List.of("evidence/a.png", "evidence/b.log"),
                ManualExecutionService.parseAttachments(json));
    }

    @Test
    void 坏附件数据当空列表() {
        assertEquals(List.of(), ManualExecutionService.parseAttachments(null));
        assertEquals(List.of(), ManualExecutionService.parseAttachments(""));
        assertEquals(List.of(), ManualExecutionService.parseAttachments("不是 JSON"));
        assertEquals(List.of(), ManualExecutionService.parseAttachments("{\"a\":1}"),
                "不是数组就当空，不能让一条脏数据把整条记录读不出来");
    }

    // ---------------------------------------------------------------- fake

    private static final class FakeRepository implements ManualExecutionRepository {

        private final Map<Long, ManualExecution> rows = new LinkedHashMap<>();
        private long seq = 0;

        @Override
        public long insert(ManualExecution execution, Long operatorId) {
            long id = ++seq;
            rows.put(id, copyWith(execution, id, execution.steps()));
            return id;
        }

        @Override
        public void update(ManualExecution execution, Long operatorId) {
            rows.put(execution.id(), execution);
        }

        @Override
        public void updateStep(long executionId, ManualExecution.StepResult step,
                               Long operatorId) {
            ManualExecution current = rows.get(executionId);
            if (current == null) {
                return;
            }
            List<ManualExecution.StepResult> steps = new ArrayList<>();
            for (ManualExecution.StepResult item : current.steps()) {
                steps.add(item.stepNo() == step.stepNo() ? step : item);
            }
            rows.put(executionId, copyWith(current, executionId, List.copyOf(steps)));
        }

        @Override
        public Optional<ManualExecution> find(long projectId, long executionId) {
            return Optional.ofNullable(rows.get(executionId));
        }

        @Override
        public List<ManualExecution> listByRun(long projectId, long runId) {
            return rows.values().stream().filter(r -> r.runId() == runId).toList();
        }

        @Override
        public List<ManualExecution> listByCase(long projectId, long caseId, Instant since,
                                                int limit) {
            return rows.values().stream().filter(r -> r.caseId() == caseId).toList();
        }

        @Override
        public long countByVerdict(long projectId, String verdict, Instant since) {
            return rows.values().stream()
                    .filter(r -> verdict.equals(r.verdict())).count();
        }

        private static ManualExecution copyWith(ManualExecution source, long id,
                                                List<ManualExecution.StepResult> steps) {
            return new ManualExecution(id, source.projectId(), source.runId(), source.caseId(),
                    source.executorId(), source.executorName(), source.startedAt(),
                    source.finishedAt(), source.envNote(), source.verdict(), source.note(),
                    steps, source.createdAt(), source.updatedAt());
        }
    }
}
