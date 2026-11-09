package com.qa.autotest.common.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 缓存开关与容量（EW32-03）。默认开启、TTL 30 秒、最多 512 个项目键。
 *
 * <p>关闭方式：{@code app.cache.enabled=false} 或环境变量 {@code APP_CACHE_ENABLED=false}；
 * 关闭后所有读路径直接回源，用于「关缓存功能一致」的等价回归。
 */
@Component
@ConfigurationProperties(prefix = "app.cache")
public class CacheProperties {

    private boolean enabled = true;
    private int ttlSeconds = 30;
    private int maxEntries = 512;

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

    public int getMaxEntries() {
        return maxEntries;
    }

    public void setMaxEntries(int maxEntries) {
        this.maxEntries = maxEntries;
    }
}
