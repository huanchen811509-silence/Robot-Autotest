package com.qa.autotest.application.asset;

import com.qa.autotest.application.host.HostAccess;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.asset.OneShotCredential;
import org.springframework.stereotype.Service;

/**
 * 手工采集 / 一次性凭证通道（v5.7.0 / W57-39、40、42）。
 *
 * <p>三条互不干扰的分支：
 * <ol>
 *   <li><b>老分支</b>（{@code credentialId != null}）：原样委托既有
 *       {@link GlobalAssetService#collect}，{@code HostInventoryService.accessOf} 一行不碰，
 *       行为零改动；</li>
 *   <li><b>另存为凭据</b>：默认关闭，必须显式 {@code saveAsCredential=true} 才走既有
 *       {@link GlobalAssetService#createCredential} 加密录入通道（明文只在入参出现一次，
 *       随即加密入库，不进日志 / 审计 / 响应）；</li>
 *   <li><b>一次性凭证</b>（默认）：材料只走 POST body，构造成内存 {@link OneShotCredential}
 *       后即结束，<b>不写 DB / 日志 / 审计 / 响应 / 证据包 / 异常堆栈</b>。</li>
 * </ol>
 *
 * <p>响应 {@link ManualCollectOutcome} 是显式元信息 record，字段里没有任何材料位；
 * 失败异常一律固定文案，绝不拼接请求体内容。{@link OneShotCredential#toString()} 打码，
 * 即便有人误把它塞进日志也不会带出明文。
 *
 * <p><b>如实说明</b>：一次性分支只做「受理 + 内存材料」，不触发真实远程采集
 * （避免真实 SSH 与材料跨进程流转）；实际采集由调用方在同一调用栈内自行持有内存对象完成。
 */
@Service
public class ManualCredentialCollectService {

    private final GlobalAssetService assets;
    private final AccessControlService access;

    /** 单一公开构造器（构造器注入，禁字段注入）。 */
    public ManualCredentialCollectService(GlobalAssetService assets, AccessControlService access) {
        this.assets = assets;
        this.access = access;
    }

    public ManualCollectOutcome collect(long assetId, ManualCollectRequest request) {
        access.requireGlobalEnter();
        ManualCollectRequest req = request == null
                ? new ManualCollectRequest(null, null, null, null, null, null,
                        false, false, null)
                : request;

        // 老分支：credentialId 非空 = 用凭据库里的既有凭证，行为零改动。
        if (req.credentialId() != null) {
            GlobalAssetService.CollectResult result =
                    assets.collect(assetId, req.credentialId(), req.username(), req.confirm());
            return new ManualCollectOutcome(assetId, false, "credentialId",
                    result == null ? "UNKNOWN" : result.status(), "-");
        }

        // 显式「另存为凭据」：默认关闭，走既有加密录入，不回显任何材料。
        if (req.saveAsCredential()) {
            String name = req.saveAsName() == null || req.saveAsName().isBlank()
                    ? "manual-" + assetId + "-" + System.nanoTime() : req.saveAsName();
            GlobalAssetService.CredentialView view = assets.createCredential(
                    new GlobalAssetService.CreateCredentialRequest(name,
                            req.credType() == null ? "ssh" : req.credType(), req.username(),
                            materialOf(req), req.connectOptions()));
            return new ManualCollectOutcome(assetId, false, "saveAsCredential",
                    view == null ? "UNKNOWN" : "SAVED", "-");
        }

        // 一次性分支：材料只在内存里活一次，不写任何输出通道。
        OneShotCredential credential;
        try {
            credential = new OneShotCredential(req.username(), req.password(), req.privateKey(),
                    req.credType(), req.connectOptions());
        } catch (IllegalArgumentException ex) {
            // 固定文案，绝不把 request 的任何字段拼进异常消息（失败不回显）
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "一次性凭证缺少口令或私钥");
        }
        String channel = HostAccess.channelOf(credential.credType());
        return new ManualCollectOutcome(assetId, true, "oneShot", "ACCEPTED", channel);
    }

    private static String materialOf(ManualCollectRequest req) {
        return req.password() != null && !req.password().isBlank()
                ? req.password() : req.privateKey();
    }

    /** POST body：材料字段只在这里出现；响应 record 里不含它们。 */
    public record ManualCollectRequest(Long credentialId, String username, String password,
                                        String privateKey, String credType, String connectOptions,
                                        boolean confirm, boolean saveAsCredential,
                                        String saveAsName) {
    }

    /** 响应：只有元信息，没有任何材料位。 */
    public record ManualCollectOutcome(long assetId, boolean oneShot, String mode,
                                        String status, String channel) {
    }
}
