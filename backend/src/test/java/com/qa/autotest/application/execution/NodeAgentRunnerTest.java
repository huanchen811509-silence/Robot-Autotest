package com.qa.autotest.application.execution;

import com.qa.autotest.application.node.NodeAgentRunner;
import com.qa.autotest.application.node.NodeTask;
import com.qa.autotest.application.node.TaskSigner;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NODX-03 执行端 agent 单测：执行委托（命令仍由 PabotCommandBuilder 生成）、
 * 回传链路（output.xml + argv + 令牌头）、执行异常的失败回传、任务验签。
 */
class NodeAgentRunnerTest {

    @TempDir
    Path workspace;

    private HttpServer master;
    private final ConcurrentLinkedQueue<Map.Entry<String, String>> uploads =
            new ConcurrentLinkedQueue<>();

    @BeforeEach
    void setUp() throws IOException {
        TenantContext.clear();
        master = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        master.createContext("/api/agent/result", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            uploads.add(Map.entry(
                    exchange.getRequestHeaders().getFirst("X-Node-Token"), body));
            byte[] resp = "{\"code\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        master.start();
    }

    @AfterEach
    void tearDown() {
        master.stop(0);
        TenantContext.clear();
    }

    @Test
    @DisplayName("验签：key=sha256(明文令牌) 正例通过、错签名/错 key 拒绝")
    void 任务验签() {
        String token = "a".repeat(64);
        String key = TaskSigner.sha256Hex(token);
        String payload = "{\"runId\":\"RUN-X\"}";
        String signature = TaskSigner.sign(key, payload);
        assertTrue(TaskSigner.verify(key, payload, signature));
        assertFalse(TaskSigner.verify(key, payload + " ", signature), "body 变一位即失效");
        assertFalse(TaskSigner.verify(TaskSigner.sha256Hex("b".repeat(64)), payload, signature),
                "错 key 签不出同一签名");
        assertFalse(TaskSigner.verify(key, payload, null));
    }

    @Test
    @DisplayName("执行委托：execute 被调且 cmd.nodeId 为空（节点侧跑本机链路，防递归投递）")
    void 执行委托同参数() {
        RecordingExecutions executions = fixtures(List.of());
        NodeTask task = task(executions.resultUrl());
        executions.runner.run(task);

        assertEquals(task.runId(), executions.received.get().runId());
        assertEquals(task.projectId(), executions.receivedCmd.get().projectId());
        assertEquals(null, executions.receivedCmd.get().nodeId(),
                "节点侧必须置空 nodeId，否则 executeOnNode 递归投递");
        assertEquals(task.suitePaths(), executions.receivedCmd.get().suitePaths(),
                "任务参数原样传给执行链路（同参数命令的前提）");
    }

    @Test
    @DisplayName("回传链路：output.xml 文本 + argv + X-Node-Token 头全部到主控")
    void 回传完整() throws IOException {
        RecordingExecutions executions = fixtures(List.of());
        // 预置 output.xml（模拟执行链路产物）
        Path runDir = workspace.resolve("artifacts").resolve("RUN-AG1");
        Files.createDirectories(runDir.resolve("shard-0"));
        Files.writeString(runDir.resolve("shard-0").resolve("output.xml"),
                "<robot><suite/></robot>");

        NodeTask task = task(executions.resultUrl());
        executions.runner.run(task);

        Map.Entry<String, String> upload = uploads.poll();
        assertTrue(upload != null, "必须有回传");
        assertEquals("t".repeat(64), upload.getKey(), "回传必须带节点明文令牌头");
        String body = upload.getValue();
        assertTrue(body.contains("RUN-AG1"), body);
        assertTrue(body.contains("<robot><suite/></robot>"), "output.xml 原文必须回传");
        assertTrue(body.contains("pabot"), "argv 里应含 pabot 可执行文件名（命令 diff 取证）");
        assertTrue(body.contains("\"argv\""), body);
    }

    @Test
    @DisplayName("执行异常 → 回传 success=false 且 reason 带 node_agent_execute_failed")
    void 执行异常回传失败() {
        RecordingExecutions executions = fixtures(List.of(), true);
        executions.runner.run(task(executions.resultUrl()));

        Map.Entry<String, String> upload = uploads.poll();
        assertTrue(upload != null, "异常也必须回传，主控才知道 agent 活着但任务失败");
        assertTrue(upload.getValue().contains("node_agent_execute_failed"),
                upload.getValue());
    }

    // ---- 基建 ----

    private NodeTask task(String resultUrl) {
        return new NodeTask("RUN-AG1", 7L, List.of("suites/a.robot"), 1, null,
                List.of(), 900, 0, null, null, resultUrl, "http://127.0.0.1:1");
    }

    private RecordingExecutions fixtures(List<String> testNames) {
        return fixtures(testNames, false);
    }

    private RecordingExecutions fixtures(List<String> testNames, boolean explode) {
        InMemoryRunRepository repo = new InMemoryRunRepository();
        ExecutionProperties props = ExecutionTestSupport.props();
        props.setWorkspaceRoot(workspace.toString());
        props.setArtifactsRoot(workspace.resolve("artifacts").toString());
        RunService runs = new RunService(repo, ExecutionTestSupport.access(),
                ExecutionTestSupport.audit(), new RunIdGenerator(),
                props, ExecutionTestSupport.CLOCK);
        // 预置 run（agent 只执行已存在的 run）
        try {
            repo.insert(new RunRecord(0L, "RUN-AG1", 7L, null, RunState.QUEUED, 0, 1, 0,
                    0, 0, 0, null, null, null, null, java.time.Instant.now(), null, null, null),
                    "RUN-AG1", "req-ag1", 1L);
        } catch (Exception ignored) {
            // 内存仓储重复插入容忍
        }
        RecordingExecutions executions = new RecordingExecutions(runs, props, explode);
        NodeAgentRunner runner = new NodeAgentRunner(runs, executions,
                new PabotCommandBuilder(), props, 3L, "t".repeat(64),
                "http://127.0.0.1:" + master.getAddress().getPort());
        executions.runner = runner;
        executions.resultUrl = "http://127.0.0.1:" + master.getAddress().getPort()
                + "/api/agent/result";
        return executions;
    }

    /** 覆盖 execute 的桩执行器：记录入参，可注入异常。 */
    private static final class RecordingExecutions extends RunExecutionService {
        final AtomicReference<RunRecord> received = new AtomicReference<>();
        final AtomicReference<RunService.CreateRunCommand> receivedCmd =
                new AtomicReference<>();
        final boolean explode;
        NodeAgentRunner runner;
        String resultUrl;

        String resultUrl() {
            return resultUrl;
        }

        private RecordingExecutions(RunService runs, ExecutionProperties props,
                                    boolean explode) {
            super(runs, props, null, null, null, null, null, null, null,
                    null, null, ExecutionTestSupport.CLOCK, null, null, null, null, null, null);
            this.explode = explode;
        }

        @Override
        public RunRecord execute(RunRecord created, RunService.CreateRunCommand cmd) {
            received.set(created);
            receivedCmd.set(cmd);
            if (explode) {
                throw new IllegalStateException("boom");
            }
            return created;
        }
    }
}
