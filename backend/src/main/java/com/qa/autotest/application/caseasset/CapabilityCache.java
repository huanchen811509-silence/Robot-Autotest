package com.qa.autotest.application.caseasset;

import com.qa.autotest.common.cache.CacheProperties;
import com.qa.autotest.common.cache.TtlCache;
import com.qa.autotest.domain.caseasset.CapabilityStat;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * 能力索引缓存（EW32-03）：键是 projectId，值为「该项目的 capability 聚合」。
 *
 * <p>租户隔离靠 key：不同项目的缓存互不可见。写路径（新建/修改/删除/批量/导入）
 * 调用 {@link #invalidate(long)}，保证变更后第一次请求就读到新值。
 */
@Component
public class CapabilityCache {

    private final TtlCache<Long, List<CapabilityStat>> cache;

    public CapabilityCache(CacheProperties properties) {
        this.cache = new TtlCache<>(properties.getMaxEntries(),
                Duration.ofSeconds(properties.getTtlSeconds()), properties.isEnabled());
    }

    public List<CapabilityStat> get(long projectId, Supplier<List<CapabilityStat>> loader) {
        return cache.get(projectId, key -> List.copyOf(loader.get()));
    }

    public void invalidate(long projectId) {
        cache.invalidate(projectId);
    }

    public TtlCache.Stats stats() {
        return cache.stats();
    }
}
