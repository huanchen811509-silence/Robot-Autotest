package com.qa.autotest.api.host;

import com.qa.autotest.application.host.HostInventoryCollector;
import com.qa.autotest.application.host.HostInventoryService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.host.HostInventoryRecord;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 主机清单 API（FR-v3.11-01）。所有读写先做项目访问校验，跨项目一律 403。
 *
 * <p>采集是**显式 POST**：没有「进页面自动采一次」这种路径。采集会真的在目标机上执行命令，
 * 自动触发等于把「用户以为只读了一页」变成「机器上跑了一串命令」。
 */
@RestController
@RequestMapping("/api/asset-host-inventory")
public class HostInventoryController {

    private final HostInventoryService inventory;
    private final AccessControlService access;

    public HostInventoryController(HostInventoryService inventory, AccessControlService access) {
        this.inventory = inventory;
        this.access = access;
    }

    /** 最近采集记录（摘要，不含 spec_json）。 */
    @GetMapping
    public ApiResponse<List<HostInventoryRecord>> list(@RequestParam long projectId,
                                                       @RequestParam(required = false) Long deviceId,
                                                       @RequestParam(defaultValue = "20") int limit) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(inventory.list(projectId, deviceId, limit));
    }

    /**
     * 单次采集详情（含 spec_json：10 类 + 每项命令/时间/值或原因）。
     *
     * <p>路径写成 `{id:\\d+}` 而不是 `{id}`：否则 `GET /collect` 会先匹配到这里，
     * 把 `collect` 当 id 转数字失败，回一个莫名其妙的 400——而「采集只能 POST」
     * 的正确答复是 405。这类「字面路径被变量路径吃掉」的坑，只在真打接口时暴露。
     */
    @GetMapping("/{id:\\d+}")
    public ApiResponse<HostInventoryRecord> detail(@PathVariable long id, @RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(inventory.find(projectId, id));
    }

    /** 显式触发采集。 */
    @PostMapping("/collect")
    public ApiResponse<HostInventoryRecord> collect(@RequestBody CollectRequest request) {
        if (request == null || request.projectId() == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "projectId 不能为空");
        }
        access.requireProjectAccess(request.projectId());
        return ApiResponse.ok(inventory.collect(request.projectId(), request.host(),
                request.deviceId(), request.operatorId()));
    }

    /**
     * 采集命令白名单（判据④：命令清单要能被看到，而不是藏在代码里）。
     *
     * <p>只回命令与分类，不回任何采集结果——这条接口是「我们会在你机器上执行什么」的答案，
     * 越早让人看见越好。
     */
    @GetMapping("/commands")
    public ApiResponse<Map<String, String>> commands(@RequestParam long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(HostInventoryCollector.COMMANDS);
    }

    /** @param operatorId 可选：不传则记为空（审计列可空，不猜操作人） */
    public record CollectRequest(Long projectId, String host, Long deviceId, Long operatorId) {
    }
}
