package com.qa.autotest.application.execution;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 产物四条件：退出码不可信，只有「存在 + size>1024 + 可解析 + totalTests>0」才算通过。 */
class ArtifactVerifierTest {

    private final ArtifactVerifier verifier = new ArtifactVerifier(new OutputXmlProbe());

    @TempDir
    Path tmp;

    private Path fixture(String name) throws Exception {
        return Path.of(getClass().getClassLoader()
                .getResource("fixtures/output-xml/" + name).toURI());
    }

    @Test
    void 文件不存在返回_missing() {
        ArtifactVerifier.VerifyResult result = verifier.verify(tmp.resolve("none.xml"));
        assertFalse(result.success());
        assertEquals("missing", result.reason());
        assertEquals(0, result.sizeBytes());
    }

    @Test
    void 小于等于_1024_字节返回_too_small() throws Exception {
        Path small = tmp.resolve("small.xml");
        Files.writeString(small, "<robot/>", StandardCharsets.UTF_8);
        ArtifactVerifier.VerifyResult result = verifier.verify(small);
        assertFalse(result.success());
        assertEquals("too_small", result.reason());
    }

    @Test
    void 非法XML返回_parse_failed() throws Exception {
        Path broken = tmp.resolve("broken.xml");
        // 超过 1024 字节且标签不闭合，确保命中「可解析」这一条而不是 too_small。
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><robot>");
        for (int i = 0; i < 100; i++) {
            sb.append("<!-- padding line ").append(i).append(" -->");
        }
        sb.append("<suite></robot>");
        Files.writeString(broken, sb.toString(), StandardCharsets.UTF_8);
        ArtifactVerifier.VerifyResult result = verifier.verify(broken);
        assertFalse(result.success());
        assertEquals("parse_failed", result.reason());
    }

    @Test
    void 没有_test_节点返回_no_tests_executed() throws Exception {
        ArtifactVerifier.VerifyResult result = verifier.verify(fixture("empty.xml"));
        assertFalse(result.success());
        assertEquals("no_tests_executed", result.reason());
        assertTrue(result.sizeBytes() > 1024);
    }

    @Test
    void 正常产物返回_ok_且统计测试数() throws Exception {
        ArtifactVerifier.VerifyResult result = verifier.verify(fixture("ok.xml"));
        assertTrue(result.success());
        assertEquals("ok", result.reason());
        assertEquals(2, result.totalTests());
        assertTrue(result.sizeBytes() > 1024);
    }

    // ---- 以下四条针对 2026-11-04 实测发现的两个漏洞 ----
    // 背景：pabot 4 分片里 kill -9 掉一个，output.xml 只剩 6 条（应 8 条），
    // 旧逻辑只判 totalTests>0 → 6>0 → 返回 ok，用例凭空消失却被判全绿。

    @Test
    void 少于期望条数返回_partial_results() throws Exception {
        // ok.xml 内有 2 条用例，这里声称应跑 3 条
        ArtifactVerifier.VerifyResult result = verifier.verify(fixture("ok.xml"), 3);
        assertFalse(result.success());
        assertEquals("partial_results", result.reason());
        assertEquals(2, result.totalTests());
        assertEquals(3, result.expectedTests());
    }

    @Test
    void 多于期望条数返回_unexpected_extra() throws Exception {
        ArtifactVerifier.VerifyResult result = verifier.verify(fixture("ok.xml"), 1);
        assertFalse(result.success());
        assertEquals("unexpected_extra", result.reason());
    }

    @Test
    void 期望条数为零时不做条数校验() throws Exception {
        // 调用方不知道应跑多少条时，不能因此判失败（旧行为必须保持）
        ArtifactVerifier.VerifyResult result = verifier.verify(fixture("ok.xml"), 0);
        assertTrue(result.success());
        assertEquals("ok", result.reason());
    }

    @Test
    void 分片产物为空返回_shard_incomplete_且指出编号() throws Exception {
        Path out = tmp.resolve("run");
        for (int i = 0; i < 4; i++) {
            Path dir = out.resolve("pabot_results").resolve(String.valueOf(i));
            Files.createDirectories(dir);
            Path xml = dir.resolve("output.xml");
            if (i == 1) {
                // 被 kill -9 的分片：pabot 会建出 0 字节的 output.xml（实测如此）
                Files.writeString(xml, "", StandardCharsets.UTF_8);
            } else {
                Files.write(xml, new byte[2048]);
            }
        }
        ArtifactVerifier.ShardIntegrity result = verifier.checkShards(out);
        assertFalse(result.ok());
        assertEquals("shard_incomplete", result.reason());
        assertEquals(List.of(1), result.emptyShards());
    }

    @Test
    void 分片齐全返回_ok() throws Exception {
        Path out = tmp.resolve("run-ok");
        for (int i = 0; i < 2; i++) {
            Path dir = out.resolve("pabot_results").resolve(String.valueOf(i));
            Files.createDirectories(dir);
            Files.write(dir.resolve("output.xml"), new byte[2048]);
        }
        ArtifactVerifier.ShardIntegrity result = verifier.checkShards(out);
        assertTrue(result.ok());
        assertEquals("ok", result.reason());
        assertTrue(result.emptyShards().isEmpty());
    }

    @Test
    void 识别单用例超时的失败消息() throws Exception {
        // FR-v0.4-24：RF 命中 Test Timeout 的固定文案落在 test > status > msg 上
        StringBuilder body = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><robot><suite name=\"S\"><test name=\"T\">"
                        + "<status status=\"FAIL\"><msg level=\"FAIL\">"
                        + "Test timeout 30 seconds exceeded.</msg></status>"
                        + "</test></suite>");
        for (int i = 0; i < 100; i++) {
            body.append("<!-- padding line ").append(i).append(" -->");
        }
        body.append("</robot>");
        Path xml = tmp.resolve("timeout.xml");
        Files.writeString(xml, body.toString(), StandardCharsets.UTF_8);

        assertEquals(1, new OutputXmlProbe().timedOutTests(xml));
        assertTrue(verifier.testTimedOut(xml));
        assertEquals(0, new OutputXmlProbe().timedOutTests(fixture("ok.xml")));
        assertFalse(verifier.testTimedOut(tmp.resolve("none.xml")));
    }

    @Test
    void 没有分片目录时不判失败_避免误伤单分片与替身() {
        // 单分片执行、测试替身、换用别的执行器都可能没有 pabot_results，
        // 这里判失败会误伤一大片；只在「有分片」时校验它们是否完整。
        ArtifactVerifier.ShardIntegrity result = verifier.checkShards(tmp.resolve("nothing"));
        assertTrue(result.ok());
        assertEquals("no_shard_dir", result.reason());
    }

    @Test
    void 三态统计只认_test_的直接子_status() throws Exception {
        // v5.24.0：样本里 <kw> 内部也有 <status>，若误用文档级 getElementsByTagName("status")
        // 会把关键字状态也算成用例（这里会从 5 条变成 9 条）。
        OutputXmlProbe.Stats stats = new OutputXmlProbe().stats(fixture("mixed-status.xml"));
        assertEquals(5, stats.total(), "5 个 <test> 节点");
        assertEquals(2, stats.passed());
        assertEquals(1, stats.failed());
        assertEquals(2, stats.skipped(), "SKIP 与 NOT RUN 都计入跳过，不能混进失败");
        assertTrue(stats.known());
        // 硬口径：三态相加必须等于总数，否则 run 表的 total = p + f + s 就不成立。
        assertEquals(stats.total(), stats.passed() + stats.failed() + stats.skipped());
    }

    @Test
    void 产物校验把三态统计透传出去() throws Exception {
        // 产物「有效」与「用例全绿」是两件事：这里 success=true（产物四条件满足），
        // 但统计里明明有失败与跳过。调用方必须能同时拿到这两个结论，才不会把它们混为一谈。
        ArtifactVerifier.VerifyResult result = verifier.verify(fixture("mixed-status.xml"));
        assertTrue(result.success());
        assertEquals("ok", result.reason());
        assertEquals(5, result.stats().total());
        assertEquals(1, result.stats().failed());
    }

    @Test
    void 产物缺失时统计为未知而不是全零可信() throws Exception {
        // 「没读到」与「跑了 0 条」必须能区分：known=false 时调用方不得据此写 0。
        ArtifactVerifier.VerifyResult result = verifier.verify(tmp.resolve("none.xml"));
        assertFalse(result.stats().known());
        assertEquals(0, result.stats().total());
    }
}
