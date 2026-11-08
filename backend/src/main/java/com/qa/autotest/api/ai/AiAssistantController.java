package com.qa.autotest.api.ai;

import com.qa.autotest.application.ai.AiAssistantConfigService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 助手配置 API（v2.5 / FR-AI-10..13）。
 *
 * <p><b>整组接口仅管理员</b>，读也不例外：哪怕只回掩码，端点 URL 与模型名也属于
 * 基础设施信息，没有理由让普通账号看到。越权一律 403，不泄露「有没有这条配置」。
 *
 * <p>写操作与「测试连接」都进审计（{@code audit_log}），摘要里只有
 * {@code key=updated|cleared|unchanged}，**不含密钥值**。
 */
@RestController
@RequestMapping("/api/ai/assistants")
public class AiAssistantController {

    private final AiAssistantConfigService configs;
    private final AccessControlService access;

    public AiAssistantController(AiAssistantConfigService configs, AccessControlService access) {
        this.configs = configs;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        requireAdmin();
        return ApiResponse.ok(configs.list());
    }

    @PutMapping("/{key}")
    public ApiResponse<Map<String, Object>> save(@PathVariable String key,
                                                  @RequestBody(required = false) SaveRequest body) {
        requireAdmin();
        SaveRequest req = body == null ? new SaveRequest(null, null, null, null, null) : body;
        return ApiResponse.ok(configs.save(key, req.baseUrl(), req.model(), req.apiKey(),
                Boolean.TRUE.equals(req.clearKey()),
                req.enabled() == null || Boolean.TRUE.equals(req.enabled()),
                null));
    }

    @PostMapping("/{key}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable String key) {
        requireAdmin();
        return ApiResponse.ok(configs.test(key));
    }

    private void requireAdmin() {
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "AI 助手配置仅管理员可访问");
        }
    }

    /**
     * 保存请求。字段刻意全部可空：
     * <ul>
     *   <li>{@code apiKey} 为空 = 不改密钥（页面显示掩码时不会把真 key 回传）；</li>
     *   <li>{@code clearKey=true} = 明确清空，与「没填」区分；</li>
     *   <li>{@code enabled} 为空 = 保持启用。</li>
     * </ul>
     */
    public record SaveRequest(String baseUrl, String model, String apiKey, Boolean clearKey,
                              Boolean enabled) {
    }
}
