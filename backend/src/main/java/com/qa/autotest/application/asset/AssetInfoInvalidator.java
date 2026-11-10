package com.qa.autotest.application.asset;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 资产变更 → 连接/拓扑缓存失效（v4.3.0 / FR-v4.3-05 R4）。
 *
 * <p>用 {@code AFTER_COMMIT} 而不是事务内监听：写事务回滚时缓存不该被清（本来也没变），
 * 提交后才清；没有事务上下文时（如单测直调服务）用 {@code fallbackExecution=true} 仍然执行一次，
 * 保证「无论有没有事务都不会留下过期缓存」。
 *
 * <p>失效只清项目级聚合；片段级键含内容指纹，资产一改指纹就变，旧片段不会被命中。
 */
@Component
public class AssetInfoInvalidator {

    private final AssetInfoService assets;

    public AssetInfoInvalidator(AssetInfoService assets) {
        this.assets = assets;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAssetChanged(AssetChangedEvent event) {
        assets.invalidate(event.projectId());
    }
}
