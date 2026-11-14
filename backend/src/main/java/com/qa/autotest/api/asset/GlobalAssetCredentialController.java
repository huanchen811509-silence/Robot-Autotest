package com.qa.autotest.api.asset;

import com.qa.autotest.application.asset.CredentialVerificationService;
import com.qa.autotest.application.asset.ManualCredentialCollectService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公共资产的凭证下拉 / 验证 / 手工采集（v5.7.0 / W57-38、39、40、42）。
 *
 * <p>挂在 {@code /api/global/assets} 下的<b>独立 Controller</b>：既有
 * {@link GlobalAssetController} 只有一个公开构造器且承担导入 / 列举 / 引用治理，
 * 按硬规矩不新增构造器、不删原有构造器，三个新端点落到同路径的另一个类。
 * 所有 {@code {id}} 写成 {@code \d+}，与既有 {@code /{type}/{id}/references} 等
 * 「同段数不同语义」的映射明确区分（沿用 GlobalAssetController 里已验证过的做法）。
 */
@RestController
@RequestMapping("/api/global/assets")
public class GlobalAssetCredentialController {

    private final CredentialVerificationService verifications;
    private final ManualCredentialCollectService manualCollect;
    private final AccessControlService access;

    public GlobalAssetCredentialController(CredentialVerificationService verifications,
                                           ManualCredentialCollectService manualCollect,
                                           AccessControlService access) {
        this.verifications = verifications;
        this.manualCollect = manualCollect;
        this.access = access;
    }

    /** 设备选凭证下拉：四级排序 + 验证时间 / 结果 / 绑定设备数；>50 条用 keyword 服务端搜。 */
    @GetMapping("/{id:\\d+}/credentials/options")
    public ApiResponse<CredentialVerificationService.CredentialOptions> options(
            @PathVariable long id,
            @RequestParam(required = false) String keyword) {
        access.requireGlobalEnter();
        return ApiResponse.ok(verifications.options(id, keyword));
    }

    /** 验证一条凭据：需 confirm=true + asset:update；本机目标由服务层直接拒绝。 */
    @PostMapping("/{id:\\d+}/credentials/{credId:\\d+}/verify")
    public ApiResponse<CredentialVerificationService.VerificationResult> verify(
            @PathVariable long id,
            @PathVariable long credId,
            @RequestParam(required = false) String channel,
            @RequestParam(defaultValue = "false") boolean confirm) {
        if (!confirm) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "凭证验证需要二次确认：请求需带 confirm=true");
        }
        access.requireGlobalEnter();
        access.require(PermissionResource.ASSET, PermissionAction.UPDATE);
        return ApiResponse.ok(verifications.verify(id, credId, channel));
    }

    /** 手工采集：credentialId 老分支 / 一次性凭证（POST body）/ 显式另存为凭据。 */
    @PostMapping("/{id:\\d+}/collect/manual")
    public ApiResponse<ManualCredentialCollectService.ManualCollectOutcome> collectManual(
            @PathVariable long id,
            @RequestBody(required = false)
            ManualCredentialCollectService.ManualCollectRequest body) {
        access.requireGlobalEnter();
        return ApiResponse.ok(manualCollect.collect(id, body));
    }
}
