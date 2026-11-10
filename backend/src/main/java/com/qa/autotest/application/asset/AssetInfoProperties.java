package com.qa.autotest.application.asset;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 连接与拓扑派生缓存配置（v4.3.0 / FR-v4.3-05）。
 *
 * <p>复用窗口默认 12 小时（{@code asset-info.cache.ttl-seconds=43200}）。窗口只影响
 * 「多久后自然重算」，与「资产变更立即失效」互不替代：写操作仍会主动清掉项目聚合缓存。
 * 关闭缓存（{@code asset-info.cache.enabled=false}）时直接直算，功能不降级，仅性能下降。
 */
@Component
@ConfigurationProperties(prefix = "asset-info.cache")
public class AssetInfoProperties {

    private boolean enabled = true;
    private int ttlSeconds = 43200;
    private int maxProjectEntries = 512;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(int ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public int getMaxProjectEntries() {
        return maxProjectEntries;
    }

    public void setMaxProjectEntries(int maxProjectEntries) {
        this.maxProjectEntries = maxProjectEntries;
    }
}
