package com.qa.autotest.application.observability;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.ExecutionShardRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.execution.ExecutionShard;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** W4 运行时间线：run 级事件与分片事件的合并顺序、字段透出与缺数据时的退化。 */
class RunTimelineServiceTest {

    private static final long PROJECT = 29L;
    private static final String RUN = "RUN-W4";

    private RunRepository runs;
    private ExecutionShardRepository shards;
    private AccessControlService access;
    private RunTimelineService service;

    @BeforeEach
    void setUp() {
        runs = mock(RunRepository.class);
        shards = mock(ExecutionShardRepository.class);
        access = mock(AccessControlService.class);
        service = new RunTimelineService(runs, shards, access);
    }

    private static RunRecord run(Instant enqueued, Instant started, Instant finished) {
        return new RunRecord(1L, RUN, PROJECT, null, RunState.FINISHED, 5, 2, 1,
                9, 8, 1, null, null, null, "/runs/RUN-W4",
                enqueued, started, finished, "all", null);
    }

    private static ExecutionShard shard(int index, String state, Instant started, Instant finished,
                                        int attempt, long fencing, int tests, int fails) {
        return new ExecutionShard(100L + index, RUN, PROJECT, 11L, index,
                List.of("suites/s" + index + ".robot"), state, "node-11", null,
                fencing, attempt, tests, fails, started, finished);
    }

    @Test
    @DisplayName("事件按时间合并：入队 → 开始 → 分片领取/收口 → 结束")
    void mergesRunAndShardEventsInTimeOrder() {
        when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.of(run(
                Instant.parse("2026-11-07T01:00:00Z"),
                Instant.parse("2026-11-07T01:01:00Z"),
                Instant.parse("2026-11-07T01:05:00Z"))));
        when(shards.listByRun(PROJECT, RUN)).thenReturn(List.of(
                shard(1, ExecutionShard.FAILED,
                        Instant.parse("2026-11-07T01:02:05Z"),
                        Instant.parse("2026-11-07T01:03:00Z"), 2, 2L, 4, 1),
                shard(0, ExecutionShard.DONE,
                        Instant.parse("2026-11-07T01:01:10Z"),
                        Instant.parse("2026-11-07T01:02:00Z"), 1, 1L, 5, 0)));

        RunTimelineService.RunTimelineView view = service.timeline(PROJECT, RUN);

        assertThat(view.runId()).isEqualTo(RUN);
        // 库里的 run_status 是小写，接口必须归一成大写（前端 TERMINAL 判定依赖它）
        assertThat(view.state()).isEqualTo("FINISHED");
        assertThat(view.events()).extracting(RunTimelineService.TimelineEvent::type)
                .containsExactly("RUN_ENQUEUED", "RUN_STARTED", "SHARD_LEASED", "SHARD_DONE",
                        "SHARD_LEASED", "SHARD_FAILED", "RUN_FINISHED");
        assertThat(view.events().get(3).shardIndex()).isZero();
        assertThat(view.events().get(3).testCount()).isEqualTo(5);
        assertThat(view.events().get(4).attempt()).isEqualTo(2);
        assertThat(view.events().get(4).fencingToken()).isEqualTo(2);
        assertThat(view.events().get(6).testCount()).isEqualTo(9);
        assertThat(view.events().get(6).failCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("PENDING 分片没有时间点 → 不产生事件，不影响排序")
    void pendingShardWithoutTimestampsProducesNoEvent() {
        when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.of(run(
                Instant.parse("2026-11-07T01:00:00Z"), null, null)));
        when(shards.listByRun(PROJECT, RUN)).thenReturn(List.of(
                shard(0, ExecutionShard.PENDING, null, null, 0, 0, 0, 0)));

        RunTimelineService.RunTimelineView view = service.timeline(PROJECT, RUN);

        assertThat(view.events()).extracting(RunTimelineService.TimelineEvent::type)
                .containsExactly("RUN_ENQUEUED");
        assertThat(view.startedAt()).isNull();
        assertThat(view.finishedAt()).isNull();
    }

    @Test
    @DisplayName("run 不存在 → NOT_FOUND，而不是空时间线")
    void missingRunThrowsNotFound() {
        when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.timeline(PROJECT, RUN))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }
}
