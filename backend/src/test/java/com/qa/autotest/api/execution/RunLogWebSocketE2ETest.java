package com.qa.autotest.api.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.execution.RunLogService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * EXE-04 实时日志 WebSocket 端到端验收（真实容器 + 真实 WS 协议）。
 *
 * <p>WBS 原缺口：「真实 WebSocket 端到端、断线重连与 2000 行上限需在本地浏览器抽验；
 * {@code log-latency.txt} 只覆盖服务端 flush，不含网络传输」。
 * 本测试起真实 Tomcat（随机端口），用 JDK WebSocket 客户端走真实网络栈，
 * 覆盖四件事：<b>连上补 200 行</b>、<b>增量 100ms 批推</b>、<b>断开重连重新补发</b>、
 * <b>环形缓冲 2000 行上限</b>。比浏览器抽验更强的地方是能精确断言行数与序号连续。
 *
 * <p>需要真实库（{@code robot_it}）：未提供 {@code DB_PASSWORD} 时整体跳过，
 * 不让没配库的环境因这一条而红。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+",
        disabledReason = "该用例需要真实 MariaDB（robot_it），未配置 DB_PASSWORD 时跳过")
class RunLogWebSocketE2ETest {

    private static final int RECONNECT_LINES = 200;
    private static final int RING_CAPACITY = 2000;
    private static final long WAIT_MS = 8_000;

    @LocalServerPort
    private int port;

    @Autowired
    private RunLogService logs;

    private final ObjectMapper json = new ObjectMapper();

    /** 收帧器：累积所有文本帧，并保留 socket 引用以便测试里主动断开。 */
    private static final class Collector implements WebSocket.Listener {
        private final List<String> frames = new CopyOnWriteArrayList<>();
        private final StringBuilder partial = new StringBuilder();
        private volatile WebSocket socket;

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            synchronized (partial) {
                partial.append(data);
                if (last) {
                    frames.add(partial.toString());
                    partial.setLength(0);
                }
            }
            ws.request(1);
            return null;
        }

        /** 丢弃已收帧：把「补发 / 探针」与「待测的增量批次」隔开。 */
        void clear() {
            frames.clear();
        }

        List<JsonNode> parsed(ObjectMapper mapper) {
            List<JsonNode> out = new ArrayList<>();
            for (String frame : frames) {
                try {
                    out.add(mapper.readTree(frame));
                } catch (Exception ignored) {
                    // 非 JSON 帧不计入（正常路径不会有）
                }
            }
            return out;
        }

        long lineCount(ObjectMapper mapper) {
            long total = 0;
            for (JsonNode node : parsed(mapper)) {
                total += node.path("lines").size();
            }
            return total;
        }

        List<String> texts(ObjectMapper mapper) {
            List<String> out = new ArrayList<>();
            for (JsonNode node : parsed(mapper)) {
                node.path("lines").forEach(line -> out.add(line.path("text").asText()));
            }
            return out;
        }
    }

    private Collector connect(String runId) throws Exception {
        Collector collector = new Collector();
        collector.socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/runs/" + runId), collector)
                .get(10, TimeUnit.SECONDS);
        return collector;
    }

    private static void await(String message, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        assertTrue(condition.getAsBoolean(), message);
    }

    @Test
    @DisplayName("EXE04-WS-01 连上即补发最近 200 行，且首尾行正确")
    void reconnectReplaysRecent200() throws Exception {
        String runId = "RUN-WS-01";
        for (int i = 1; i <= 250; i++) {
            logs.publish(runId, "stdout", "line-" + i);
        }

        Collector collector = connect(runId);
        await("连上后应补发 200 行", () -> collector.lineCount(json) >= RECONNECT_LINES);

        List<String> texts = collector.texts(json);
        assertEquals(RECONNECT_LINES, texts.size(), "补发行数必须正好 200");
        assertEquals("line-51", texts.get(0), "补发应从倒数第 200 行开始");
        assertEquals("line-250", texts.get(texts.size() - 1), "补发到最新一行结束");
    }

    @Test
    @DisplayName("EXE04-WS-02 增量推送：单帧 ≤50 行，网络层真收到全部 120 行")
    void incrementalPushArrives() throws Exception {
        String runId = "RUN-WS-02";
        Collector collector = connect(runId);
        await("连接建立后订阅应就绪", () -> collector.socket != null && !collector.socket.isOutputClosed());

        // 客户端 WS 已连上 != 服务端 subscribe 已完成。若此刻就发 120 行，服务端随后的
        // reconnect(200) 会把它们当「补发」一帧推回（补发帧最多 200 行），判据就变成
        // 在验补发而不是验增量 —— 负载高时偶发「单帧 120 行」假红（B5 门禁实测）。
        // 先发一行探针并等它真正到达，确认订阅与发送水位都就绪，再清空已收帧专测增量。
        logs.publish(runId, "stdout", "ws-probe-ready");
        await("探针行到达说明服务端订阅已就绪", () -> collector.lineCount(json) >= 1);
        collector.clear();

        for (int i = 1; i <= 120; i++) {
            logs.publish(runId, "stdout", "inc-" + i);
        }
        await("120 行应在多批内推完", () -> collector.texts(json).size() >= 120);

        int maxPerFrame = 0;
        for (JsonNode node : collector.parsed(json)) {
            maxPerFrame = Math.max(maxPerFrame, node.path("lines").size());
        }
        assertTrue(maxPerFrame <= 50, "单帧不得超过 50 行，实测 " + maxPerFrame);
        assertTrue(maxPerFrame > 0, "必须至少收到一帧");
    }

    @Test
    @DisplayName("EXE04-WS-03 断开后重连仍补发 200 行（订阅随断开解除，重连不依赖老会话）")
    void reconnectAfterClose() throws Exception {
        String runId = "RUN-WS-03";
        for (int i = 1; i <= 220; i++) {
            logs.publish(runId, "stdout", "re-" + i);
        }

        Collector first = connect(runId);
        await("首次连接应补发 200 行", () -> first.lineCount(json) >= RECONNECT_LINES);
        first.socket.abort();

        Collector second = connect(runId);
        await("重连应再次补发 200 行", () -> second.lineCount(json) >= RECONNECT_LINES);
        List<String> texts = second.texts(json);
        assertEquals(RECONNECT_LINES, texts.size(), "重连补发同样正好 200 行");
        assertEquals("re-21", texts.get(0), "重连补发同样从倒数第 200 行开始");
    }

    @Test
    @DisplayName("EXE04-WS-04 环形缓冲上限 2000 行：超限只保留最新 2000 行且序号连续")
    void ringBufferKeepsLast2000() throws Exception {
        String runId = "RUN-WS-04";
        for (int i = 1; i <= 2100; i++) {
            logs.publish(runId, "stdout", "ring-" + i);
        }

        List<RunLogService.LogLine> recent = logs.recent(runId, 5000);
        assertEquals(RING_CAPACITY, recent.size(), "环形缓冲上限必须是 2000 行");
        assertEquals("ring-101", recent.get(0).text(), "最旧一行应是第 101 行（2100-2000）");
        assertEquals("ring-2100", recent.get(recent.size() - 1).text(), "最新一行应是第 2100 行");
        for (int i = 1; i < recent.size(); i++) {
            assertEquals(recent.get(i - 1).seq() + 1, recent.get(i).seq(),
                    "序号必须连续，不允许丢行静默");
        }
    }
}
