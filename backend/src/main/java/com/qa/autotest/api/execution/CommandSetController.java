package com.qa.autotest.api.execution;

import com.qa.autotest.application.execution.monitor.CommandKind;
import com.qa.autotest.application.execution.monitor.CommandRecord;
import com.qa.autotest.application.execution.monitor.CommandSetService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 命令集合查询（FR-v5.3-02 / R4 / W53-02）。
 *
 * <p>四个过滤维度（用例 / endpoint / kind / 退出码）+ 两次 run diff，都是 GET：
 * 命令集合是执行副产物，查询是只读行为，不需要写接口。
 *
 * <p><b>返回的是「预览」不是原文</b>：{@code argv} 已脱敏，{@code stdout} 只给指纹
 * （R5）。要看完整输出请走证据文件——那条路径受文件权限约束，而 API 受角色约束，
 * 两条路径分开才能各自收紧。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/runs/{runId}/commands")
public class CommandSetController {

    private final CommandSetService service;
    private final AccessControlService access;

    public CommandSetController(CommandSetService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<CommandRecord>> list(
            @PathVariable long projectId,
            @PathVariable String runId,
            @RequestParam(required = false) String caseCode,
            @RequestParam(required = false) String endpoint,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) Integer exitCode,
            @RequestParam(defaultValue = "false") boolean failedOnly,
            @RequestParam(defaultValue = "500") int limit) {
        access.requireProjectAccess(projectId);
        CommandSetService.Query query = new CommandSetService.Query(
                blankToNull(caseCode), blankToNull(endpoint), parseKind(kind),
                exitCode, failedOnly, limit);
        return ApiResponse.ok(service.query(runId, query));
    }

    /**
     * 两次 run 的同用例命令差异。
     *
     * <p>{@code caseCode} 省略时比全部命令——排障时常用的正是「这个 run 跟昨天那个
     * 成功的 run 比，多了/少了哪条命令」。
     */
    @GetMapping("/diff")
    public ApiResponse<CommandSetService.CommandDiff> diff(
            @PathVariable long projectId,
            @PathVariable String runId,
            @RequestParam String with,
            @RequestParam(required = false) String caseCode) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.diff(runId, with, blankToNull(caseCode)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 未知 kind 一律当「不过滤」会让用户以为过滤生效了；这里明确报错。 */
    private static CommandKind parseKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        try {
            return CommandKind.valueOf(kind.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new com.qa.autotest.common.BusinessException(
                    com.qa.autotest.common.ErrorCode.INVALID_ARGUMENT,
                    "未知命令类别 " + kind + "，可选：collect / configure / traffic / verify / cleanup / other");
        }
    }
}
