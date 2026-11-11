package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.node.ExecutionNodeService;
import com.qa.autotest.application.repository.RunRepository;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2 收尾证据：**2 节点等价实测**（进程内假 agent，不引 MQ）。
 *
 * <p>要回答的问题只有一个：把同一批套件切成两片、由两个节点分别领完并回写之后，
 * 结果与「单节点整轮跑」是否等价。等价的口径写死为四条，缺一即 FAIL：
 *
 * <ol>
 *   <li>不重：所有分片的 suitePaths 并集里每个路径只出现一次；</li>
 *   <li>不漏：并集 == 规划前的全集，missing 为 0；</li>
 *   <li>不丢：每片回写成功（终端状态非 LOST），聚合 complete=true；</li>
 *   <li>口径等价：testCount / failCount 求和与单节点执行一致，run 终态正确推进。</li>
 * </ol>
 *
 * <p>诚实边界：这是**进程内双 node-id** 的等价验证，不是两台物理机。
 * 物理双机未实跑（147 已退役、149 为唯一主力）；真实网络分区与 agent 进程崩溃
 * 属于 W3 故障注入的范围，见 {@code docs/evidence/v2.2/README.md}。
 */
@ExtendWith(MockitoExtension.class)
class ShardTwoNodeEquivalenceTest {

    private static final long PROJECT = 1L;
    private static final String RUN = "RUN-W2-EQ";
    private static final Instant NOW = Instant.parse("2026-11-07T03:00:00Z");
    private static final long NODE_A = 11L;
    private static final long NODE_B = 12L;

    private static final List<String> SUITES = List.of(
            "suites/s01.robot", "suites/s02.robot", "suites/s03.robot", "suites/s04.robot",
            "suites/s05.robot", "suites/s06.robot", "suites/s07.robot", "suites/s08.robot");

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
    }

    @Test
    @DisplayName("两节点分片：套件不重不漏，计数与单节点等价，run 正常收口")
    void twoNodeShardingIsEquivalentToSingleNodeRun() {
        when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.of(run()));

        ShardService.RunShardsView planned = service.plan(PROJECT, RUN, SUITES, 2, 8L);
        assertThat(planned.totalShards()).isEqualTo(2);
        log("plan: nodeCount=2 shards=%d suites=%d", planned.totalShards(), SUITES.size());

        // 假 agent 两阶段：先两节点各领一片（此时片都还没被占，必须领到不同的片），
        // 再各自跑完回写；第二轮再领应得 null（本轮没有更多片）
        ShardService.ClaimResult claimA =
                service.claim(NODE_A, "token-" + NODE_A, PROJECT, RUN, 300);
        ShardService.ClaimResult claimB =
                service.claim(NODE_B, "token-" + NODE_B, PROJECT, RUN, 300);
        assertThat(claimA).as("节点 %d 应能领到分片", NODE_A).isNotNull();
        assertThat(claimB).as("节点 %d 应能领到分片", NODE_B).isNotNull();
        assertThat(claimA.shardIndex()).as("两节点不得领到同一片").isNotEqualTo(claimB.shardIndex());

        List<String> executed = new ArrayList<>();
        int tests = 0;
        for (ShardService.ClaimResult claim : List.of(claimA, claimB)) {
            log("node claimed shard %d paths=%s token=%d attempt=%d",
                    claim.shardIndex(), claim.suitePaths(),
                    claim.fencingToken(), claim.attempt());
        }

        for (ShardService.ClaimResult claim : List.of(claimA, claimB)) {
            long nodeId = claim.shardIndex() == claimA.shardIndex() ? NODE_A : NODE_B;
            // 每片 4 个套件、每套件 1 条用例，全部通过；结果与单节点整轮跑一致
            int shardTests = claim.suitePaths().size();
            service.complete(nodeId, "token-" + nodeId, PROJECT, RUN,
                    claim.shardIndex(), claim.fencingToken(), true, shardTests, 0);
            executed.addAll(claim.suitePaths());
            tests += shardTests;
        }

        assertThat(service.claim(NODE_A, "token-" + NODE_A, PROJECT, RUN, 300))
                .as("两片都被领完后 node-%d 不应再领到", NODE_A).isNull();
        assertThat(service.claim(NODE_B, "token-" + NODE_B, PROJECT, RUN, 300))
                .as("两片都被领完后 node-%d 不应再领到", NODE_B).isNull();

        // ① 不重：并集大小 == 执行总数
        Set<String> distinct = new HashSet<>(executed);
        assertThat(executed).hasSize(SUITES.size());
        assertThat(distinct).hasSize(SUITES.size());
        // ② 不漏：并集 == 全集
        assertThat(distinct).containsExactlyInAnyOrderElementsOf(SUITES);
        log("coverage: executed=%d distinct=%d duplicates=%d missing=%d",
                executed.size(), distinct.size(),
                executed.size() - distinct.size(), SUITES.size() - distinct.size());

        // ③ 不丢 + ④ 口径等价
        ShardService.RunShardsView done = service.view(PROJECT, RUN);
        assertThat(done.complete()).isTrue();
        assertThat(done.terminalShards()).isEqualTo(done.totalShards());
        assertThat(done.testCount()).isEqualTo(tests).isEqualTo(SUITES.size());
        assertThat(done.failCount()).isZero();
        assertThat(done.aggregateState()).isEqualTo("DONE");
        log("aggregate: complete=%s tests=%d fails=%d state=%s",
                done.complete(), done.testCount(), done.failCount(), done.aggregateState());

        // 回写 run：聚合口径必须与单节点整轮跑一致（8 用例 / 0 失败）
        verify(runService).updateCounts(RUN, SUITES.size(), SUITES.size(), 0, "/runs/" + RUN);
        verify(runService).transition(eq(RUN), any(RunState.class), any(RunService.TransitionContext.class));
    }

    @Test
    @DisplayName("两节点中一片有失败：聚合 FAILED，计数按两片求和，不因分片而丢失失败数")
    void failureInOneShardFailsRunButCountsAreComplete() {
        when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.of(run()));
        service.plan(PROJECT, RUN, SUITES, 2, 8L);

        ShardService.ClaimResult a = service.claim(NODE_A, "token-" + NODE_A, PROJECT, RUN, 300);
        ShardService.ClaimResult b = service.claim(NODE_B, "token-" + NODE_B, PROJECT, RUN, 300);
        service.complete(NODE_A, "token-" + NODE_A, PROJECT, RUN, a.shardIndex(), a.fencingToken(),
                false, a.suitePaths().size(), 1);
        service.complete(NODE_B, "token-" + NODE_B, PROJECT, RUN, b.shardIndex(), b.fencingToken(),
                true, b.suitePaths().size(), 0);

        ShardService.RunShardsView done = service.view(PROJECT, RUN);
        assertThat(done.complete()).isTrue();
        assertThat(done.aggregateState()).isEqualTo("FAILED");
        assertThat(done.testCount()).isEqualTo(SUITES.size());
        assertThat(done.failCount()).isEqualTo(1);
        log("failure-in-one-shard: tests=%d fails=%d state=%s",
                done.testCount(), done.failCount(), done.aggregateState());

        verify(runService).updateCounts(RUN, SUITES.size(), SUITES.size() - 1, 1, "/runs/" + RUN);
        verify(runService).transition(eq(RUN), eq(RunState.FAILED), any(RunService.TransitionContext.class));
    }

    @Test
    @DisplayName("两节点均衡：套件数均分，片大小差不超过 1")
    void shardsAreBalancedAcrossTwoNodes() {
        when(runs.findByRunId(PROJECT, RUN)).thenReturn(Optional.of(run()));
        service.plan(PROJECT, RUN, SUITES, 2, 8L);

        List<Integer> sizes = repo.listByRun(PROJECT, RUN).stream()
                .map(row -> row.suitePaths().size())
                .toList();
        assertThat(sizes).hasSize(2);
        assertThat(Math.abs(sizes.get(0) - sizes.get(1))).isLessThanOrEqualTo(1);
        log("balance: shard sizes=%s", sizes);
    }

    /** 只用于把等价结论打进 surefire 输出，作为 docs/evidence/v2.2/raw 的原始证据。 */
    private static void log(String format, Object... args) {
        System.out.println("[two-node] " + String.format(format, args));
    }

    private static RunRecord run() {
        return new RunRecord(1L, RUN, PROJECT, null, RunState.RUNNING, 0, 0, 0, 0, 0, 0,
                null, null, null, "/runs/" + RUN, NOW, null, null, null, null);
    }

    private static ExecutionNode node(long id) {
        return new ExecutionNode(id, "node-" + id, "hash", ExecutionNode.ONLINE,
                true, true, 2, NOW, NOW, null);
    }
}
