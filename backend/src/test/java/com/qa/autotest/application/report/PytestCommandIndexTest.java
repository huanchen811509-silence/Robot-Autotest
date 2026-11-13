package com.qa.autotest.application.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.application.kernel.pytest.PytestResultNormalizer;
import com.qa.autotest.domain.kernel.CaseStatus;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
import com.qa.autotest.domain.kernel.NormalizedRun;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 逐用例命令索引与补录（FR-v5.6-21）。
 *
 * <p>目录名规则必须与用例库 {@code Runtime.begin_case} 的
 * {@code re.sub(r"[^A-Za-z0-9_.-]+", "_", node_id)} 逐字一致——不一致的表现是
 * 「一条命令都取不到」，而 junit 里看不出来，属于静默失败，所以这里按真实 nodeid 断言。
 */
class PytestCommandIndexTest {

    private static final String SCRIPT = "tests/nic/new_scripts/test_im_fun_052.py";
    private static final String FUNCTION = "test_im_fun_052_config_snapshot_diff";
    private static final String NODE_ID = SCRIPT + "::" + FUNCTION;

    private final PytestCommandEnricher enricher = new PytestCommandEnricher();

    @Test
    @DisplayName("按框架目录规则取到首条命令，来源标为 COMMAND_INDEX")
    void 取到命令(@TempDir Path root) throws IOException {
        writeIndex(root, NODE_ID, "gpu01_10.202.54.101",
                line("ip -o link show dev ens17np0") + line("ethtool -k ens17np0"));

        CaseCommandExtractor.Extracted found =
                PytestCommandIndex.lookup(root, List.of(NODE_ID));

        assertTrue(found.hasCommand());
        assertEquals("ip -o link show dev ens17np0", found.text());
        assertEquals(CaseCommandExtractor.SOURCE_COMMAND_INDEX, found.source());
    }

    @Test
    @DisplayName("多条设备索引按文件名取第一份，两次读取结果一致")
    void 多设备取首份(@TempDir Path root) throws IOException {
        writeIndex(root, NODE_ID, "gpu01", line("ip link show"));
        writeIndex(root, NODE_ID, "gpu02", line("ethtool -i ens17np0"));

        assertEquals("ip link show", PytestCommandIndex.lookup(root, List.of(NODE_ID)).text());
        assertEquals("ip link show", PytestCommandIndex.lookup(root, List.of(NODE_ID)).text());
    }

    @Test
    @DisplayName("目录不存在 / 坏行 / 空 command 一律回落 NONE，不抛异常")
    void 缺证据不抛异常(@TempDir Path root) throws IOException {
        assertFalse(PytestCommandIndex.lookup(root, List.of(NODE_ID)).hasCommand());
        assertFalse(PytestCommandIndex.lookup(null, List.of(NODE_ID)).hasCommand());
        assertFalse(PytestCommandIndex.lookup(root, List.of()).hasCommand());

        Path raw = PytestCommandIndex.rawDir(root, NODE_ID);
        Files.createDirectories(raw);
        Files.writeString(raw.resolve("gpu01.commands.jsonl"),
                "{这一行不是合法 JSON} 坏行\n{\"command\": \"  \"}\n", StandardCharsets.UTF_8);

        assertFalse(PytestCommandIndex.lookup(root, List.of(NODE_ID)).hasCommand());
    }

    @Test
    @DisplayName("命令原文过脱敏：明文口令不得出口（与日志同一套规则）")
    void 脱敏(@TempDir Path root) throws IOException {
        writeIndex(root, NODE_ID, "gpu01",
                line("sshpass -p SuperSecret123 ssh root@10.0.0.1 'ip link'"));

        String text = PytestCommandIndex.lookup(root, List.of(NODE_ID)).text();

        assertTrue(text.contains("ip link"), "命令主体应保留");
        assertFalse(text.contains("SuperSecret123"), "明文口令不得出口");
    }

    @Test
    @DisplayName("补录：用绑定脚本路径拼 nodeid 才能命中框架目录（junit 点分名拼不出来）")
    void 补录命中(@TempDir Path root) throws IOException {
        writeIndex(root, NODE_ID, "gpu01", line("ethtool -k ens17np0"));
        // junit 形态的 nodeId：点分 classname，直接拼目录匹配不上
        String junitNodeId = "tests.nic.new_scripts.test_im_fun_052::" + FUNCTION;

        PytestResultNormalizer.NormalizeResult enriched = enricher.enrich(root,
                normalizeResult(junitNodeId, SCRIPT));

        NormalizedCaseResult one = enriched.run().cases().get(0);
        assertEquals("ethtool -k ens17np0", one.commandText());
        assertEquals(CaseCommandExtractor.SOURCE_COMMAND_INDEX, one.commandSource());
    }

    @Test
    @DisplayName("补录幂等：已有命令（显式标记）的行不被索引覆盖")
    void 不覆盖显式标记(@TempDir Path root) throws IOException {
        writeIndex(root, NODE_ID, "gpu01", line("ethtool -k ens17np0"));
        PytestResultNormalizer.NormalizeResult source = new PytestResultNormalizer.NormalizeResult(
                new NormalizedRun("pytest", "RUN-1", List.of(new NormalizedCaseResult(
                        NODE_ID, "IM-FUN-052", CaseStatus.PASS, 1.0, null,
                        "ip link show dev ens17np0", CaseCommandExtractor.SOURCE_PYTEST_MARK, 0)),
                        Map.of()),
                List.of(new PytestResultNormalizer.CaseMapping(NODE_ID, "IM-FUN-052", SCRIPT, true)),
                0, false, Path.of("/tmp/run_metadata.json"));

        assertEquals("ip link show dev ens17np0",
                enricher.enrich(root, source).run().cases().get(0).commandText());
    }

    @Test
    @DisplayName("补录无命中时原样返回（引用相等，调用方不必拷贝）")
    void 无命中不拷贝(@TempDir Path root) {
        PytestResultNormalizer.NormalizeResult source = normalizeResult(NODE_ID, SCRIPT);

        assertSame(source, enricher.enrich(root, source));
        assertSame(source, enricher.enrich(null, source));
    }

    private static PytestResultNormalizer.NormalizeResult normalizeResult(String nodeId,
                                                                         String scriptPath) {
        return new PytestResultNormalizer.NormalizeResult(
                new NormalizedRun("pytest", "RUN-1", List.of(new NormalizedCaseResult(
                        nodeId, "IM-FUN-052", CaseStatus.PASS, 1.0, null)), Map.of()),
                List.of(new PytestResultNormalizer.CaseMapping(nodeId, "IM-FUN-052",
                        scriptPath, true)),
                0, false, Path.of("/tmp/run_metadata.json"));
    }

    /** 一行索引 JSON，字段名与框架 {@code RawSessionWriter.append} 一致。 */
    private static String line(String command) {
        return "{\"command_id\": \"abc123\", \"endpoint_key\": \"gpu01@10.202.54.101\","
                + " \"connection_name\": \"ssh-target-host\", \"command\": "
                + "\"" + command.replace("\"", "\\\"") + "\","
                + " \"purpose\": \"读取网口信息\", \"synthetic_prompt\": true}\n";
    }

    private static void writeIndex(Path root, String nodeId, String endpoint, String content)
            throws IOException {
        Path raw = PytestCommandIndex.rawDir(root, nodeId);
        Files.createDirectories(raw);
        Files.writeString(raw.resolve(endpoint + PytestCommandIndex.INDEX_SUFFIX), content,
                StandardCharsets.UTF_8);
    }
}
