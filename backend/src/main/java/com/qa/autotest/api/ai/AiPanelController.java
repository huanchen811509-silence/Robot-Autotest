package com.qa.autotest.api.ai;

import com.qa.autotest.application.ai.AiPanelService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.domain.ai.AiActionResult;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** AI 面板 API（W6 / FR-AI-07）。所有动作先做项目访问校验并过 ai.enabled 闸门；发起执行由服务层强制二次确认。 */
@RestController
@RequestMapping("/api/ai")
public class AiPanelController {

    private final AiPanelService panel;
    private final AccessControlService access;

    public AiPanelController(AiPanelService panel, AccessControlService access) {
        this.panel = panel;
        this.access = access;
    }

    /** 发现端点：明确禁用时也返回 enabled=false，供前端隐藏入口；其余端点一律闸门拦截。 */
    @GetMapping("/state")
    public ApiResponse<Map<String, Object>> state(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(panel.state(projectId));
    }

    @PostMapping("/session")
    public ApiResponse<Long> open(@RequestParam long projectId, @RequestParam long userId) {
        access.requireProjectAccess(projectId);
        panel.requireEnabled();
        return ApiResponse.ok(panel.open(projectId, userId));
    }

    @PostMapping("/suggest")
    public ApiResponse<AiActionResult> suggest(@RequestBody SuggestRequest request) {
        access.requireProjectAccess(request.projectId());
        panel.requireEnabled();
        return ApiResponse.ok(panel.suggest(request.sessionId(), request.context()));
    }

    @PostMapping("/execute")
    public ApiResponse<AiActionResult> execute(@RequestBody ExecuteRequest request) {
        access.requireProjectAccess(request.projectId());
        panel.requireEnabled();
        return ApiResponse.ok(panel.execute(request.sessionId(), request.target(),
                Boolean.TRUE.equals(request.confirmed())));
    }

    /** FR-AI-08：传真实进程句柄，不再传 null。 */
    @PostMapping("/terminate")
    public ApiResponse<AiActionResult> terminate(@RequestParam long sessionId,
                                                  @RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        panel.requireEnabled();
        return ApiResponse.ok(panel.terminate(sessionId, panel.handleFor(sessionId)));
    }

    /** FR-AI-05：多轮对话；返回模型回答 + sessionId。 */
    @PostMapping("/chat")
    public ApiResponse<Map<String, Object>> chat(@RequestBody ChatRequest request) {
        access.requireProjectAccess(request.projectId());
        panel.requireEnabled();
        return ApiResponse.ok(panel.chat(request.projectId(), request.sessionId(),
                request.prompt()));
    }

    public record SuggestRequest(long projectId, long sessionId, Map<String, String> context) {
    }

    public record ExecuteRequest(long projectId, long sessionId, String target, Boolean confirmed) {
    }

    public record ChatRequest(long projectId, long sessionId, String prompt) {
    }
}
