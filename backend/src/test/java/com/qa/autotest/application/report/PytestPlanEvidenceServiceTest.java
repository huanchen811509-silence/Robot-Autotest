package com.qa.autotest.application.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W-EHT-07：消费用例库 fake 执行计划。
 *
 * <p>判据三件事：① 有计划时字段逐字对得上；② 没计划时说清原因（real 模式正常无计划）；
 * ③ 坏文件/非法 runId 只降级不抛，报告不能因为读计划而建不出来。
 */
class PytestPlanEvidenceServiceTest {

    private static final long PROJECT = 95L;

    private static PytestPlanEvidenceService service(Path repo) {
        return new PytestPlanEvidenceService(
                new FakeSettings(Map.of("repo_path", repo.toString())), new ObjectMapper());
    }

    @Test
    void 计划文件缺失时说明原因而不是留空白(@TempDir Path repo) {
        EhtSections.Plan plan = service(repo).read(PROJECT, "RUN-1");
        assertThat(plan.present()).isFalse();
        assertThat(plan.note()).contains("未生成执行计划");
        assertThat(plan.commands()).isEmpty();
        assertThat(plan.branches()).isEmpty();
    }

    @Test
    void 解析fake计划时连接事实与命令分支都要带出来(@TempDir Path repo) throws Exception {
        Path dir = repo.resolve("artifacts").resolve("RUN-1");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("fake_plan.json"), """
                {
                  "mode": "fake-plan-only",
                  "connection_established": false,
                  "stdout_synthesized": false,
                  "commands": [
                    {"endpoint_key":"gpu01@10.202.54.101",
                     "command":"sudo -n lspci -Dnnvv -s 45:00.0",
                     "purpose":"读取 PCIe 能力",
                     "expected_observation":"Vendor/Device ID 与 LnkCap"}
                  ],
                  "alternative_branches": [
                    {"case_id":"IM-NET-002","action":"set_link_speed",
                     "branch":{"name":"speed-32g","condition":"设备支持 32GT/s",
                               "expected_result":"链路协商到 32GT/s"}}
                  ]
                }""", StandardCharsets.UTF_8);

        EhtSections.Plan plan = service(repo).read(PROJECT, "RUN-1");
        assertThat(plan.present()).isTrue();
        assertThat(plan.mode()).isEqualTo("fake-plan-only");
        assertThat(plan.connectionEstablished()).isFalse();
        assertThat(plan.stdoutSynthesized()).isFalse();
        assertThat(plan.commands()).hasSize(1);
        assertThat(plan.commands().get(0).endpointKey()).isEqualTo("gpu01@10.202.54.101");
        assertThat(plan.commands().get(0).command()).contains("lspci");
        assertThat(plan.commands().get(0).purpose()).contains("PCIe");
        assertThat(plan.branches()).hasSize(1);
        assertThat(plan.branches().get(0).caseId()).isEqualTo("IM-NET-002");
        assertThat(plan.branches().get(0).name()).isEqualTo("speed-32g");
        assertThat(plan.branches().get(0).condition()).contains("32GT/s");
    }

    @Test
    void runId含路径分隔或上跳时拒绝读取(@TempDir Path repo) {
        assertThat(service(repo).read(PROJECT, "../../etc/passwd").present()).isFalse();
        EhtSections.Plan plan = service(repo).read(PROJECT, "../secret");
        assertThat(plan.present()).isFalse();
        assertThat(plan.note()).contains("非法");
    }

    @Test
    void 坏JSON降级为不可用而不抛异常(@TempDir Path repo) throws Exception {
        Path dir = repo.resolve("artifacts").resolve("RUN-2");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("fake_plan.json"), "{ not json", StandardCharsets.UTF_8);

        EhtSections.Plan plan = service(repo).read(PROJECT, "RUN-2");
        assertThat(plan.present()).isFalse();
        assertThat(plan.note()).contains("无法解析");
    }

    private record FakeSettings(Map<String, String> values) implements ProjectSettingRepository {

        @Override
        public Optional<String> find(long projectId, String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Map<String, String> findAll(long projectId, String prefix) {
            return values;
        }

        @Override
        public void put(long projectId, String key, String value, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void remove(long projectId, String key) {
            throw new UnsupportedOperationException();
        }
    }
}
