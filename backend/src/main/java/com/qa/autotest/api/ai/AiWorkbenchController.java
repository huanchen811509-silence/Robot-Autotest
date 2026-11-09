package com.qa.autotest.api.ai;

import com.qa.autotest.application.ai.AiStreamService;
import com.qa.autotest.application.ai.AiWorkbenchService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.AiWorkbenchRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.ai.DraftCase;
import com.qa.autotest.domain.ai.PromptTemplate;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AI 工作台 API（FR-3.3-01..08）。全部端点先做项目访问校验；配置写操作只允许管理员。 */
@RestController
@RequestMapping("/api/ai/workbench")
public class AiWorkbenchController {

    public record RunRequest(long projectId, String runId, long sessionId) {
    }

    public record ChatRequest(long projectId, long sessionId, String prompt) {
    }

    public record DraftRequest(long projectId, String requirement, String module, int count,
                               long sessionId) {
    }

    public record ApplyRequest(long projectId, List<DraftCase> drafts, boolean reviewed) {
    }

    public record ConfigRequest(long projectId, boolean enabled, int dailyQuota,
                                String defaultAgent, String defaultModel, int maxContextChars) {
    }

    public record PromptRequest(long projectId, String key, String name, String body) {
    }

    private final AiWorkbenchService workbench;
    private final AiStreamService streams;
    private final AccessControlService access;

    public AiWorkbenchController(AiWorkbenchService workbench, AiStreamService streams,
                                 AccessControlService access) {
        this.workbench = workbench;
        this.streams = streams;
        this.access = access;
    }

    @GetMapping("/config")
    public ApiResponse<AiWorkbenchRepository.Config> config(@RequestParam long projectId) {
        return ApiResponse.ok(workbench.config(projectId));
    }

    @PutMapping("/config")
    public ApiResponse<AiWorkbenchRepository.Config> saveConfig(
            @RequestBody ConfigRequest request) {
        var config = new AiWorkbenchRepository.Config(request.enabled(), request.dailyQuota(),
                request.defaultAgent(), request.defaultModel(), request.maxContextChars());
        return ApiResponse.ok(workbench.saveConfig(request.projectId(), config,
                TenantContext.userId()));
    }

    @GetMapping("/quota")
    public ApiResponse<AiWorkbenchService.QuotaView> quota(@RequestParam long projectId) {
        return ApiResponse.ok(workbench.quota(projectId));
    }

    @PostMapping("/triage")
    public ApiResponse<AiWorkbenchService.AiResult> triage(@RequestBody RunRequest request) {
        return ApiResponse.ok(workbench.triage(request.projectId(), request.runId(),
                request.sessionId(), TenantContext.userId()));
    }

    @PostMapping("/narrative")
    public ApiResponse<AiWorkbenchService.AiResult> narrative(@RequestBody RunRequest request) {
        return ApiResponse.ok(workbench.narrative(request.projectId(), request.runId(),
                request.sessionId(), TenantContext.userId()));
    }

    @PostMapping("/chat")
    public ApiResponse<AiWorkbenchService.AiResult> chat(@RequestBody ChatRequest request) {
        return ApiResponse.ok(workbench.chat(request.projectId(), request.sessionId(),
                request.prompt(), TenantContext.userId()));
    }

    @PostMapping("/case-draft")
    public ApiResponse<AiWorkbenchService.AiResult> caseDraft(@RequestBody DraftRequest request) {
        return ApiResponse.ok(workbench.caseDraft(request.projectId(), request.requirement(),
                request.module(), request.count(), request.sessionId(),
                TenantContext.userId()));
    }

    @GetMapping("/drafts")
    public ApiResponse<List<DraftCase>> drafts(@RequestParam long projectId,
                                               @RequestParam(required = false) String module,
                                               @RequestParam(defaultValue = "5") int count) {
        return ApiResponse.ok(workbench.draftCases(projectId, module, count));
    }

    @PostMapping("/case-draft/apply")
    public ApiResponse<List<Long>> apply(@RequestBody ApplyRequest request) {
        return ApiResponse.ok(workbench.applyDrafts(request.projectId(), request.drafts(),
                request.reviewed(), TenantContext.userId()));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AiWorkbenchRepository.SessionRow>> sessions(
            @RequestParam long projectId, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(workbench.sessions(projectId, TenantContext.userId(), limit));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ApiResponse<List<AiWorkbenchRepository.MessageRow>> messages(
            @PathVariable long sessionId, @RequestParam long projectId) {
        return ApiResponse.ok(workbench.messages(projectId, sessionId));
    }

    @GetMapping("/sessions/{sessionId}/export")
    public ResponseEntity<String> export(@PathVariable long sessionId,
                                         @RequestParam long projectId) {
        String markdown = workbench.export(projectId, sessionId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .body(markdown);
    }

    @GetMapping("/prompts")
    public ApiResponse<List<PromptTemplate>> prompts(@RequestParam long projectId) {
        return ApiResponse.ok(workbench.templates(projectId));
    }

    @PostMapping("/prompts")
    public ApiResponse<PromptTemplate> savePrompt(@RequestBody PromptRequest request) {
        return ApiResponse.ok(workbench.saveTemplate(request.projectId(), request.key(),
                request.name(), request.body(), TenantContext.userId()));
    }

    @DeleteMapping("/prompts")
    public ApiResponse<Boolean> deletePrompt(@RequestParam long projectId,
                                             @RequestParam String key) {
        return ApiResponse.ok(workbench.deleteTemplate(projectId, key));
    }

    @PostMapping("/chat/stream")
    public ResponseEntity<SseEmitter> stream(@RequestBody ChatRequest request) {
        access.requireProjectAccess(request.projectId());
        AiStreamService.Started started = streams.start(request.projectId(), request.sessionId(),
                request.prompt(), TenantContext.userId());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("X-AI-Stream-Id", started.streamId())
                .header("Cache-Control", "no-store")
                .body(started.emitter());
    }

    @PostMapping("/chat/stream/{streamId}/cancel")
    public ApiResponse<Boolean> cancel(@PathVariable String streamId,
                                       @RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(streams.cancel(projectId, streamId));
    }

    @GetMapping("/chat/stream/state")
    public ApiResponse<Map<String, Object>> streamState() {
        return ApiResponse.ok(streams.state());
    }
}
