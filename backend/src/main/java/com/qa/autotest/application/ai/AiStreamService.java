package com.qa.autotest.application.ai;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.ai.AiAnswer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * AI 流式对话（FR-3.3-01 / EW33-01）。
 *
 * <p>SSE 事件序列：{@code meta} → {@code citations} → 多个 {@code delta} → {@code done}
 * （异常时为 {@code error} 或 {@code cancelled}）。首字先发首块再 sleep，保证「首字 ≤ 3s」；
 * 取消与超时都会把已生成部分落库并标记 {@code CANCELLED / TIMEOUT}，不留悬挂会话。
 */
@Service
public class AiStreamService {

    private static final Logger LOG = LoggerFactory.getLogger(AiStreamService.class);

    /** 流建立结果：控制器拿 emitter，事件里的 streamId 用于取消。 */
    public record Started(String streamId, SseEmitter emitter) {
    }

    private static final class Handle {
        private final String streamId;
        private final long projectId;
        private final long sessionId;
        private final String prompt;
        private final SseEmitter emitter;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean timedOut = new AtomicBoolean(false);
        private final AtomicBoolean recorded = new AtomicBoolean(false);

        private Handle(String streamId, long projectId, long sessionId, String prompt,
                       SseEmitter emitter) {
            this.streamId = streamId;
            this.projectId = projectId;
            this.sessionId = sessionId;
            this.prompt = prompt;
            this.emitter = emitter;
        }
    }

    private final AiWorkbenchService workbench;
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "ai-sse");
        thread.setDaemon(true);
        return thread;
    });

    @Value("${ai.stream.timeout-seconds:30}")
    private int timeoutSeconds = 30;

    @Value("${ai.stream.chunk-delay-ms:18}")
    private long chunkDelayMs = 18;

    @Value("${ai.stream.chunk-size:24}")
    private int chunkSize = 24;

    public AiStreamService(AiWorkbenchService workbench) {
        this.workbench = workbench;
    }

    public Started start(long projectId, long sessionId, String prompt, Long userId) {
        long session = workbench.prepareStream(projectId, sessionId, userId);
        String streamId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter((long) timeoutSeconds * 1000);
        Handle handle = new Handle(streamId, projectId, session, prompt, emitter);
        handles.put(streamId, handle);
        emitter.onCompletion(() -> handles.remove(streamId));
        emitter.onError(error -> handles.remove(streamId));
        emitter.onTimeout(() -> {
            handle.timedOut.set(true);
            handles.remove(streamId);
        });
        executor.submit(() -> pump(handle));
        return new Started(streamId, emitter);
    }

    public boolean cancel(long projectId, String streamId) {
        Handle handle = handles.get(streamId);
        if (handle == null || handle.projectId != projectId) {
            return false;
        }
        handle.cancelled.set(true);
        return true;
    }

    public int active() {
        return handles.size();
    }

    static List<String> chunks(String text, int size) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        int step = Math.max(1, size);
        for (int i = 0; i < text.length(); i += step) {
            result.add(text.substring(i, Math.min(text.length(), i + step)));
        }
        return result;
    }

    private void pump(Handle handle) {
        long started = System.nanoTime();
        long firstTokenMs = -1;
        String partial = "";
        AiAnswer answer = null;
        try {
            send(handle, "meta", Map.of("streamId", handle.streamId,
                    "sessionId", handle.sessionId, "mode", "STREAM"));
            answer = workbench.buildChatAnswer(handle.projectId, handle.prompt);
            send(handle, "citations", answer.citations());
            List<String> chunks = chunks(answer.text(), chunkSize);
            StringBuilder built = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                if (handle.cancelled.get() || handle.timedOut.get()) {
                    break;
                }
                send(handle, "delta", chunks.get(i));
                built.append(chunks.get(i));
                if (firstTokenMs < 0) {
                    firstTokenMs = (System.nanoTime() - started) / 1_000_000;
                }
                if (i < chunks.size() - 1) {
                    Thread.sleep(Math.max(0, chunkDelayMs));
                }
            }
            partial = built.toString();
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;
            if (handle.timedOut.get()) {
                record(handle, answer, partial, "TIMEOUT", "（超时：仅返回已生成部分）");
                send(handle, "error", Map.of("code", "AI_STREAM_TIMEOUT",
                        "message", "回答超时，已保留部分内容", "elapsedMs", elapsedMs));
            } else if (handle.cancelled.get()) {
                record(handle, answer, partial, "CANCELLED", "（已取消：仅返回已生成部分）");
                send(handle, "cancelled", Map.of("elapsedMs", elapsedMs,
                        "generatedChars", partial.length()));
            } else {
                record(handle, answer, answer.text(), "STREAM", null);
                send(handle, "done", Map.of("elapsedMs", elapsedMs,
                        "firstTokenMs", firstTokenMs, "citationCount",
                        answer.citations().size(), "mode", answer.mode()));
            }
            handle.emitter.complete();
        } catch (IOException clientGone) {
            handle.cancelled.set(true);
            record(handle, answer, partial, "CANCELLED", "（客户端断开：仅返回已生成部分）");
            handle.emitter.complete();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            record(handle, answer, partial, "CANCELLED", "（中断：仅返回已生成部分）");
            handle.emitter.complete();
        } catch (BusinessException business) {
            safeSend(handle, "error", Map.of("code", business.errorCode().codeName(),
                    "message", business.getMessage()));
            handle.emitter.complete();
        } catch (Exception unexpected) {
            LOG.warn("AI 流式回答失败 streamId={}", handle.streamId, unexpected);
            safeSend(handle, "error", Map.of("code", "AI_STREAM_FAILED",
                    "message", "回答生成失败，请重试或改用同步模式"));
            handle.emitter.complete();
        } finally {
            handles.remove(handle.streamId);
        }
    }

    private void safeSend(Handle handle, String name, Object data) {
        try {
            send(handle, name, data);
        } catch (IOException ignored) {
            handle.cancelled.set(true);
        }
    }

    private void record(Handle handle, AiAnswer answer, String text, String mode, String note) {
        if (answer == null || !handle.recorded.compareAndSet(false, true)) {
            return;
        }
        String finalText = note == null ? text : text + "\n\n" + note;
        AiAnswer stored = new AiAnswer(mode, finalText, answer.facts(), answer.inferences(),
                answer.suggestions(), answer.citations(), true);
        try {
            workbench.recordStream(handle.projectId, handle.sessionId, handle.prompt, stored, mode);
        } catch (RuntimeException persistence) {
            LOG.warn("AI 流式结果落库失败 streamId={}", handle.streamId, persistence);
        }
    }

    private void send(Handle handle, String name, Object data) throws IOException {
        handle.emitter.send(SseEmitter.event().name(name).data(data));
    }

    /** 供状态端点：当前进行中的流数量 + 超时配置，便于运维核对。 */
    public Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("active", active());
        state.put("timeoutSeconds", timeoutSeconds);
        state.put("chunkDelayMs", chunkDelayMs);
        return state;
    }
}
