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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3 故障转移注入（4 类）：节点失联租约立即判失、租约超时重派、主控重启恢复、健康节点不被抢。
 *
 * <p>判定口径是「无重复、无丢失」：无论分片被重派几次，每条套件路径恰好被执行一次，
 * 聚合回写的用例数与套件总数一致；旧持有者的迟到回写必须被 fencing 拒绝。
 *
 * <p>假 agent 在进程内模拟两个 node-id，不是物理双机——物理双机的等价性在
 * {@code ShardTwoNodeEquivalenceTest} 与 v2.2 证据包里说明。
 */
@ExtendWith(MockitoExtension.class)
class ShardFailoverTest {

    private static final long PROJECT = 1L;
    private static final String RUN = "RUN-W3-FAILOVER";
    private static final long NODE_A = 11L;
    private static final long NODE_B = 12L;
    private static final Instant NOW = Instant.parse("2026-11-07T04:00:00Z");
    private static final List<String> SUITES = List.of("s1", "s2", "s3", "s4");

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
        lenient().when(nodes.authenticate(anyLong(), anyString()))
                .thenAnswer(inv -> node(inv.getArgument(0)));
        lenient().when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.of(run()));
    }

    @Test
    @DisplayName("故障 1：节点失联 → 租约立即判 LOST → 另一节点重派（attempt+1），旧写入被 fencing 拒")
    void offlineNodeLeaseIsReassignedWithoutDuplicateOrLoss() {
        service.plan(PROJECT, RUN, SUITES, 2, 8L);
        ShardService.ClaimResult a = service.claim(NODE_A, "token-a", PROJECT, RUN, 300);
        ShardService.ClaimResult b = service.claim(NODE_B, "token-b", PROJECT, RUN, 300);
        assertThat(a).isNotNull();
        assertThat(b).isNotNull();
        // B 先把自己的片跑完；此刻 A 的片还是 LEASED，run 尚未收口
        service.complete(NODE_B, "token-b", PROJECT, RUN, b.shardIndex(), b.fencingToken(),
                true, b.suitePaths().size(), 0);
        assertThat(service.view(PROJECT, RUN).aggregateState()).isEqualTo("IN_PROGRESS");

        // A 心跳失联被判 OFFLINE：租约不等超时直接判 LOST
        int affected = service.releaseLeasesOfNodes(List.of(NODE_A));
        assertThat(affected).isEqualTo(1);
        long oldToken = a.fencingToken();
        assertThat(repo.find(PROJECT, RUN, a.shardIndex()).orElseThrow().state())
                .isEqualTo(ExecutionShard.LOST);

        // 全终态但 LOST 仍可重派 → RETRYING：不跃迁、不回写总线数
        ShardService.RunShardsView waiting = service.aggregate(PROJECT, RUN);
        assertThat(waiting.aggregateState()).isEqualTo("RETRYING");
        assertThat(waiting.complete()).isFalse();
        verify(runService, never()).updateCounts(anyString(), anyInt(), anyInt(), anyInt(), any());

        // 旧持有者迟到回写：分片已不归它，必须被拒
        assertThatThrownBy(() -> service.complete(NODE_A, "token-a", PROJECT, RUN,
                a.shardIndex(), oldToken, true, a.suitePaths().size(), 0))
                .isInstanceOf(BusinessException.class);
        verify(runService, never()).updateCounts(anyString(), anyInt(), anyInt(), anyInt(), any());

        // B 重派同一片：attempt + 1、fencing + 1
        ShardService.ClaimResult redispatch =
                service.claim(NODE_B, "token-b", PROJECT, RUN, 300);
        assertThat(redispatch).isNotNull();
        assertThat(redispatch.shardIndex()).isEqualTo(a.shardIndex());
        assertThat(redispatch.attempt()).isEqualTo(a.attempt() + 1);
        assertThat(redispatch.fencingToken()).isEqualTo(oldToken + 1);

        List<String> executed = new ArrayList<>(b.suitePaths());
        service.complete(NODE_B, "token-b", PROJECT, RUN, redispatch.shardIndex(),
                redispatch.fencingToken(), true, redispatch.suitePaths().size(), 0);
        executed.addAll(redispatch.suitePaths());

        // 无重复、无丢失：4 条套件各执行一次
        Set<String> distinct = new LinkedHashSet<>(executed);
        assertThat(executed).hasSize(SUITES.size());
        assertThat(distinct).hasSameSizeAs(SUITES);

        ShardService.RunShardsView done = service.view(PROJECT, RUN);
        assertThat(done.aggregateState()).isEqualTo("DONE");
        assertThat(done.testCount()).isEqualTo(SUITES.size());
        assertThat(done.failCount()).isZero();
        verify(runService).updateCounts(RUN, SUITES.size(), SUITES.size(), 0, "/runs/" + RUN);
        verify(runService).transition(eq(RUN), eq(RunState.FINISHED),
                any(RunService.TransitionContext.class));
    }

    @Test
    @DisplayName("故障 2：租约超时未回写 → 判 LOST → 重派后只多一次尝试，不产生重复执行")
    void expiredLeaseIsRedispatchedExactlyOnce() {
        service.plan(PROJECT, RUN, SUITES, 2, 8L);
        ShardService.ClaimResult a = service.claim(NODE_A, "token-a", PROJECT, RUN, 30);
        repo.forceExpire(PROJECT, RUN, a.shardIndex(), NOW.minusSeconds(1));

        ShardService.ClaimResult b = service.claim(NODE_B, "token-b", PROJECT, RUN, 300);
        assertThat(b).isNotNull();
        assertThat(b.shardIndex()).isEqualTo(a.shardIndex());
        assertThat(b.attempt()).isEqualTo(2);
        assertThat(repo.listByRun(PROJECT, RUN))
                .filteredOn(row -> row.shardIndex() == a.shardIndex())
                .singleElement()
                .satisfies(row -> assertThat(row.state()).isEqualTo(ExecutionShard.LEASED));
    }

    @Test
    @DisplayName("故障 3：主控重启 → 遗留租约全部判 LOST，重放一次无残留（幂等）")
    void restartRecoveryReleasesAllLeasesIdempotently() {
        service.plan(PROJECT, RUN, SUITES, 2, 8L);
        service.claim(NODE_A, "token-a", PROJECT, RUN, 300);
        service.claim(NODE_B, "token-b", PROJECT, RUN, 300);
        assertThat(repo.listByRun(PROJECT, RUN))
                .allSatisfy(row -> assertThat(row.state()).isEqualTo(ExecutionShard.LEASED));

        int first = service.releaseAllLeasesOnRestart();
        assertThat(first).isEqualTo(1); // 1 个 run 受影响
        assertThat(repo.listByRun(PROJECT, RUN))
                .allSatisfy(row -> assertThat(row.state()).isEqualTo(ExecutionShard.LOST));

        int second = service.releaseAllLeasesOnRestart();
        assertThat(second).isZero(); // 无遗留 → 不写库

        // 重启后 run 侧由 RestartRecoveryService 判失败；分片侧不再悬挂 LEASED
        assertThat(service.view(PROJECT, RUN).aggregateState()).isEqualTo("RETRYING");
    }

    @Test
    @DisplayName("故障 4：健康节点持有中的分片不被误抢（只判失指定节点）")
    void healthyNodeLeaseIsNotStolen() {
        service.plan(PROJECT, RUN, SUITES, 2, 8L);
        ShardService.ClaimResult a = service.claim(NODE_A, "token-a", PROJECT, RUN, 300);
        ShardService.ClaimResult b = service.claim(NODE_B, "token-b", PROJECT, RUN, 300);

        int affected = service.releaseLeasesOfNodes(List.of(999L));
        assertThat(affected).isZero();
        assertThat(repo.find(PROJECT, RUN, a.shardIndex()).orElseThrow().state())
                .isEqualTo(ExecutionShard.LEASED);
        assertThat(repo.find(PROJECT, RUN, b.shardIndex()).orElseThrow().state())
                .isEqualTo(ExecutionShard.LEASED);
        assertThat(service.view(PROJECT, RUN).aggregateState()).isEqualTo("IN_PROGRESS");
    }

    // ---------------------------------------------------------------- helpers

    private static RunRecord run() {
        return new RunRecord(1L, RUN, PROJECT, null, RunState.RUNNING, 5, 1, 1, 0, 0, 0,
                null, null, null, "/runs/" + RUN, NOW, null, null, null, null);
    }

    private static ExecutionNode node(long id) {
        return new ExecutionNode(id, "node-" + id, "hash", ExecutionNode.ONLINE,
                true, false, 1, NOW, NOW, null);
    }
}
