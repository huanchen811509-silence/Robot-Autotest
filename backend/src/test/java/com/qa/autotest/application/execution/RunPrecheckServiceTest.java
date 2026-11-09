package com.qa.autotest.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.CredentialRepository;
import com.qa.autotest.application.repository.ExecutionNodeRepository;
import com.qa.autotest.application.repository.PlanRepository;
import com.qa.autotest.application.repository.PrecheckRepository;
import com.qa.autotest.domain.asset.Credential;
import com.qa.autotest.domain.node.ExecutionNode;
import com.qa.autotest.domain.plan.PlanRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 运行预检分级：BLOCK / WARN / OK 的判定顺序与预计耗时口径。 */
class RunPrecheckServiceTest {

    private static final long PROJECT = 1L;

    private PrecheckRepository precheck;
    private CredentialRepository credentials;
    private ExecutionNodeRepository nodes;
    private PlanRepository plans;
    private RunPrecheckService service;

    @BeforeEach
    void setUp() {
        precheck = mock(PrecheckRepository.class);
        credentials = mock(CredentialRepository.class);
        nodes = mock(ExecutionNodeRepository.class);
        plans = mock(PlanRepository.class);
        AccessControlService access = mock(AccessControlService.class);
        doNothing().when(access).bindProject(anyLong());
        when(precheck.durationSample(anyLong(), anyInt()))
                .thenReturn(new PrecheckRepository.DurationSample(0, 0));
        when(credentials.list(anyLong())).thenReturn(List.of());
        service = new RunPrecheckService(precheck, credentials, nodes, plans, access);
    }

    @Test
    @DisplayName("没有可执行用例 → BLOCK，且无历史样本时估算为 0")
    void 用例为空阻断() {
        RunPrecheckService.Result result = service.run(
                new RunPrecheckService.Request(PROJECT, null, null, null, null, null));

        assertThat(result.verdict()).isEqualTo(RunPrecheckService.BLOCK);
        assertThat(result.estimatedTotal()).isZero();
        assertThat(result.estimatedSeconds()).isZero();
        assertThat(result.sampleSize()).isZero();
        assertThat(result.checks()).filteredOn(check -> check.key().equals("EMPTY_CASES"))
                .singleElement().satisfies(check ->
                        assertThat(check.level()).isEqualTo(RunPrecheckService.BLOCK));
    }

    @Test
    @DisplayName("包含 L2/LX 用例 → BLOCK（安全级优先于凭据告警）")
    void 高危用例阻断() {
        when(precheck.countCases(eq(PROJECT), anyList())).thenReturn(3);
        when(precheck.countRiskyCases(eq(PROJECT), anyList())).thenReturn(2);

        RunPrecheckService.Result result = service.run(
                new RunPrecheckService.Request(PROJECT, null, null, List.of(1L, 2L), null, null));

        assertThat(result.verdict()).isEqualTo(RunPrecheckService.BLOCK);
        assertThat(result.checks()).filteredOn(check -> check.key().equals("SAFETY_LEVEL"))
                .singleElement().satisfies(check ->
                        assertThat(check.detail()).contains("2 条"));
    }

    @Test
    @DisplayName("指定节点离线 → BLOCK")
    void 节点离线阻断() {
        when(precheck.countCases(eq(PROJECT), anyList())).thenReturn(2);
        when(nodes.find(9L)).thenReturn(Optional.of(new ExecutionNode(
                9L, "node-9", "hash", ExecutionNode.OFFLINE, true, true, 1, null, null, null)));

        RunPrecheckService.Result result = service.run(
                new RunPrecheckService.Request(PROJECT, null, null, List.of(1L), null, 9L));

        assertThat(result.verdict()).isEqualTo(RunPrecheckService.BLOCK);
        assertThat(result.checks()).filteredOn(check -> check.key().equals("NODE_OFFLINE"))
                .singleElement().satisfies(check ->
                        assertThat(check.level()).isEqualTo(RunPrecheckService.BLOCK));
    }

    @Test
    @DisplayName("凭据缺失只告警 → WARN")
    void 凭据缺失告警() {
        when(precheck.countCases(eq(PROJECT), anyList())).thenReturn(2);

        RunPrecheckService.Result result = service.run(
                new RunPrecheckService.Request(PROJECT, null, null, List.of(1L, 2L), null, null));

        assertThat(result.verdict()).isEqualTo(RunPrecheckService.WARN);
        assertThat(result.checks()).filteredOn(check -> check.key().equals("CREDENTIAL_MISSING"))
                .singleElement().satisfies(check ->
                        assertThat(check.level()).isEqualTo(RunPrecheckService.WARN));
    }

    @Test
    @DisplayName("历史样本 × 条数 / 分片数 计算预计耗时")
    void 预计耗时() {
        when(precheck.countCases(eq(PROJECT), anyList())).thenReturn(10);
        when(precheck.durationSample(PROJECT, 100))
                .thenReturn(new PrecheckRepository.DurationSample(5, 2000));
        when(credentials.list(anyLong()))
                .thenReturn(List.of(new Credential(1L, PROJECT, "c", "password", "u", true, 1, null)));

        RunPrecheckService.Result result = service.run(
                new RunPrecheckService.Request(PROJECT, null, null, List.of(1L, 2L), 2, null));

        assertThat(result.verdict()).isEqualTo(RunPrecheckService.OK);
        assertThat(result.estimatedSeconds()).isEqualTo(10);
        assertThat(result.sampleSize()).isEqualTo(5);
    }

    @Test
    @DisplayName("计划依赖成环 → BLOCK")
    void 依赖成环阻断() {
        when(precheck.countPlanCases(PROJECT, 7L)).thenReturn(1);
        when(plans.find(PROJECT, 7L)).thenReturn(Optional.of(mock(PlanRecord.class)));
        when(plans.allDependencies(PROJECT))
                .thenReturn(Map.of(1L, List.of(2L), 2L, List.of(1L)));

        RunPrecheckService.Result result = service.run(
                new RunPrecheckService.Request(PROJECT, null, 7L, null, null, null));

        assertThat(result.verdict()).isEqualTo(RunPrecheckService.BLOCK);
        assertThat(result.checks()).filteredOn(check -> check.key().equals("PLAN_CYCLE"))
                .singleElement().satisfies(check ->
                        assertThat(check.level()).isEqualTo(RunPrecheckService.BLOCK));
    }
}
