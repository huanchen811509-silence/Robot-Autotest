package com.qa.autotest.api.kernel;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.kernel.pytest.NicModelDictService;
import com.qa.autotest.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 网卡型号字典（FR-v5.2-08 / W52-03）。
 *
 * <p>字典本身是平台级数据（型号是硬件事实，不属于某个项目），但路径挂在项目下：
 * 界面只在项目上下文里用得到它，挂顶层会让页面多带一次「拿平台权限」的判断，
 * 而权限模型里资产写权限本来就是按项目授予的。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/nic-models")
public class NicModelDictController {

    private final NicModelDictService service;
    private final AccessControlService access;

    public NicModelDictController(NicModelDictService service, AccessControlService access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<List<NicModelDictService.DictView>> list(
            @PathVariable long projectId,
            @RequestParam(defaultValue = "false") boolean includeDisabled) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.list(includeDisabled));
    }

    /** 资产型号覆盖体检：多少端口填了型号、多少对得上字典、对不上的 TopN。 */
    @GetMapping("/portfolio")
    public ApiResponse<NicModelDictService.Portfolio> portfolio(@PathVariable long projectId) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.portfolio(projectId));
    }

    @PostMapping
    public ApiResponse<NicModelDictService.DictView> create(
            @PathVariable long projectId,
            @RequestBody NicModelDictService.Command command) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.save(null, command));
    }

    @PutMapping("/{id}")
    public ApiResponse<NicModelDictService.DictView> update(
            @PathVariable long projectId,
            @PathVariable long id,
            @RequestBody NicModelDictService.Command command) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.save(id, command));
    }

    @PostMapping("/{id}/enabled")
    public ApiResponse<NicModelDictService.DictView> setEnabled(
            @PathVariable long projectId,
            @PathVariable long id,
            @RequestParam boolean value) {
        access.requireProjectAccess(projectId);
        return ApiResponse.ok(service.setEnabled(id, value));
    }
}
