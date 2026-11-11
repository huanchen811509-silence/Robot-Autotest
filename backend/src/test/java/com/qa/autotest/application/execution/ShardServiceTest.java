package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.node.ExecutionNodeService;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.execution.ExecutionShard;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.node.ExecutionNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分片服务单测（W2）：租约互斥、fencing 拒陈旧写、过期判 LOST、聚合回写 run。
 * 仓储用内存假实现（见 {@link FakeShardRepository}）——要验证的是**服务层编排与并发语义**，
 * 不是 SQL 本身（SQL 的真实行为由 {@code scripts/gates.sh} 的 MySQL 集成步骤覆盖）。
 */
@ExtendWith(MockitoExtension.class)
class ShardServiceTest {

    private static final long PROJECT = 1L;
    private static final String RUN = "RUN-W2-01";
    private static final Instant NOW = Instant.parse("2026-11-07T02:00:00Z");

    @Mock
    private RunRepository runs;
    @Mock
    private RunService runService;
    @Mock
    private ExecutionNodeService nodes;
    @Mock
    private AccessControlService access;
    @Mock
    private AuditService audit;

    private FakeShardRepository repo;
    private ShardService service;

    @BeforeEach
    void setUp() {
        repo = new FakeShardRepository();
        service = new ShardService(repo, runs, runService, nodes, access, audit,
                Clock.fixed(NOW, ZoneOffset.UTC), 3);
        org.mockito.Mockito.lenient().when(nodes.authenticate(anyLong(), anyString()))
                .thenAnswer(inv -> node(inv.getArgument(0)));
    }

    @Test
    void planCreatesShardsAndReturnsView() {
        when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.of(run(RunState.RUNNING)));

        ShardService.RunShardsView view = service.plan(PROJECT, RUN,
                List.of("s1", "s2", "s3", "s4"), 4, 9L);

        assertThat(view.totalShards()).isEqualTo(4);
        assertThat(view.complete()).isFalse();
        assertThat(view.aggregateState()).isEqualTo("IN_PROGRESS");
        assertThat(repo.rows).hasSize(4);
    }

    @Test
    void twoNodesClaimDifferentShards() {
        repo.replacePlan(RUN, PROJECT,
                ShardPolicy.plan(List.of("s1", "s2", "s3", "s4"), 2, 4), null);

        ShardService.ClaimResult first = service.claim(11L, "token", PROJECT, RUN, 300);
        ShardService.ClaimResult second = service.claim(12L, "token", PROJECT, RUN, 300);
        ShardService.ClaimResult third = service.claim(13L, "token", PROJECT, RUN, 300);

        assertThat(first.shardIndex()).isEqualTo(0);
        assertThat(second.shardIndex()).isEqualTo(1);
        assertThat(first.fencingToken()).isEqualTo(1L);
        assertThat(third).isNull(); // 两片都被占满
        assertThat(repo.find(PROJECT, RUN, 0).orElseThrow().nodeId()).isEqualTo(11L);
        assertThat(repo.find(PROJECT, RUN, 1).orElseThrow().nodeId()).isEqualTo(12L);
    }

    @Test
    void staleFencingTokenIsRejected() {
        repo.replacePlan(RUN, PROJECT, ShardPolicy.plan(List.of("s1", "s2"), 2, 4), null);
        service.claim(11L, "token", PROJECT, RUN, 300);
        long firstToken = repo.find(PROJECT, RUN, 0).orElseThrow().fencingToken();
        // 模拟租约过期后被节点 12 重抢 → 令牌 +1
        repo.forceExpire(PROJECT, RUN, 0, NOW.minusSeconds(1));
        service.claim(12L, "token", PROJECT, RUN, 300);
        assertThat(repo.find(PROJECT, RUN, 0).orElseThrow().nodeId()).isEqualTo(12L);

        assertThatThrownBy(() -> service.complete(11L, "token", PROJECT, RUN,
                0, firstToken, true, 2, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分片不属于节点");
    }

    @Test
    void staleTokenAfterSameNodeAttemptIsRejectedByRepository() {
        repo.replacePlan(RUN, PROJECT, ShardPolicy.plan(List.of("s1", "s2"), 2, 4), null);
        service.claim(11L, "token", PROJECT, RUN, 300);
        long oldToken = repo.find(PROJECT, RUN, 0).orElseThrow().fencingToken();
        repo.forceExpire(PROJECT, RUN, 0, NOW.minusSeconds(1));
        service.claim(11L, "token", PROJECT, RUN, 300); // 同节点重抢，令牌 +1

        assertThatThrownBy(() -> service.complete(11L, "token", PROJECT, RUN,
                0, oldToken, true, 2, 0))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fencing_token 过期");
        verify(runService, never()).updateCounts(anyString(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void aggregateSumsCountsAndFailsRunWhenAnyShardFailed() {
        when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.of(run(RunState.RUNNING)));
        repo.replacePlan(RUN, PROJECT, ShardPolicy.plan(List.of("s1", "s2"), 2, 4), null);
        ShardService.ClaimResult a = service.claim(11L, "token", PROJECT, RUN, 300);
        ShardService.ClaimResult b = service.claim(12L, "token", PROJECT, RUN, 300);
        service.complete(11L, "token", PROJECT, RUN, a.shardIndex(), a.fencingToken(),
                true, 5, 0);
        service.complete(12L, "token", PROJECT, RUN, b.shardIndex(), b.fencingToken(),
                false, 3, 1);

        verify(runService).updateCounts(RUN, 8, 7, 1, "/runs/" + RUN);
        verify(runService).transition(eq(RUN), any(RunState.class), any(RunService.TransitionContext.class));
        ShardService.RunShardsView view = service.view(PROJECT, RUN);
        assertThat(view.complete()).isTrue();
        assertThat(view.aggregateState()).isEqualTo("FAILED");
    }

    @Test
    void aggregateReportsInProgressBeforeAllTerminal() {
        repo.replacePlan(RUN, PROJECT, ShardPolicy.plan(List.of("s1", "s2"), 2, 4), null);
        service.claim(11L, "token", PROJECT, RUN, 300);

        ShardService.RunShardsView view = service.aggregate(PROJECT, RUN);

        assertThat(view.complete()).isFalse();
        verify(runService, never()).updateCounts(anyString(), anyInt(), anyInt(), anyInt(), any());
        verify(runService, never()).transition(anyString(), any(), any());
    }

    @Test
    void expiredLeaseBecomesLostAndWaitsForRedispatch() {
        repo.replacePlan(RUN, PROJECT,
                ShardPolicy.plan(List.of("s1", "s2"), 1, 4), null);
        service.claim(11L, "token", PROJECT, RUN, 30);
        repo.forceExpire(PROJECT, RUN, 0, NOW.minusSeconds(1));

        ShardService.RunShardsView view = service.aggregate(PROJECT, RUN);
        assertThat(view.shards().get(0).state()).isEqualTo(ExecutionShard.LOST);
        // W3：attempt 未用尽 → 还能重派，先不跃迁也不回写总线数
        assertThat(view.aggregateState()).isEqualTo("RETRYING");
        assertThat(view.complete()).isFalse();
        verify(runService, never()).updateCounts(anyString(), anyInt(), anyInt(), anyInt(), any());
        verify(runService, never()).transition(anyString(), any(), any());
    }

    @Test
    void lostShardFailsRunOnlyAfterAttemptsExhausted() {
        when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.of(run(RunState.RUNNING)));
        repo.replacePlan(RUN, PROJECT,
                ShardPolicy.plan(List.of("s1", "s2"), 1, 4), null);
        service.claim(11L, "token", PROJECT, RUN, 30);
        repo.forceExpire(PROJECT, RUN, 0, NOW.minusSeconds(1));
        // 模拟第 3 次派发也失联：attempt 已达上限，不能再重派
        repo.forceAttempt(PROJECT, RUN, 0, 3);

        ShardService.RunShardsView view = service.aggregate(PROJECT, RUN);
        assertThat(view.aggregateState()).isEqualTo("FAILED");
        assertThat(view.complete()).isTrue();
        verify(runService).updateCounts(RUN, 0, 0, 0, "/runs/" + RUN);
        verify(runService).transition(eq(RUN), any(RunState.class), any(RunService.TransitionContext.class));
    }

    // ---------------------------------------------------------------- helpers

    private static RunRecord run(RunState state) {
        return new RunRecord(1L, RUN, PROJECT, null, state, 5, 1, 1, 0, 0, 0,
                null, null, null, "/runs/" + RUN, NOW, null, null, null, null);
    }

    private static ExecutionNode node(long id) {
        return new ExecutionNode(id, "node-" + id, "hash", ExecutionNode.ONLINE,
                true, false, 1, NOW, NOW, null);
    }
}
