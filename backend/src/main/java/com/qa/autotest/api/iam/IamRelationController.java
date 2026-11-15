package com.qa.autotest.api.iam;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.IamRelationService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 关系聚合读接口（v5.9.0 / FR-v5.9-05 / W59-16）。
 *
 * <p>只读、幂等、排序稳定：同一 {@code dimension + id} 两次响应逐字节相同，前端三处显示
 * （用户组页「已授权项目」、用户页「参与项目/所属组」、项目关联页计数）全部读这一个端点。
 *
 * <p>权限：`user:write` 能力（管理员域）。用能力码而不是资源矩阵，是为了让**前端菜单显隐**
 * 与**后端 403** 同一判据——两套判据迟早会分叉（本版修的另一类缺陷正是这个）。
 */
@RestController
@RequestMapping("/api/iam")
public class IamRelationController {

    private final IamRelationService relations;
    private final AccessControlService access;

    public IamRelationController(IamRelationService relations, AccessControlService access) {
        this.relations = relations;
        this.access = access;
    }

    @GetMapping("/relations")
    public ApiResponse<?> relations(@RequestParam String dimension,
                                    @RequestParam(required = false) Long id) {
        access.requireCapability("user:write");
        Long target = id != null && id > 0 ? id : TenantContext.userId();
        if (target == null || target <= 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "缺少 id 且当前无登录用户");
        }
        return ApiResponse.ok(relations.of(dimension, target));
    }
}
