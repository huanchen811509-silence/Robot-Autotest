package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.cache.CacheProperties;
import com.qa.autotest.domain.caseasset.CapabilityStat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** EW32-03 · 能力索引缓存：项目隔离、写后失效、权限不被缓存绕过、关缓存等价。 */
class CapabilityServiceCacheTest {

    private final TestCaseRepository cases = mock(TestCaseRepository.class);
    private final AccessControlService access = mock(AccessControlService.class);

    private CapabilityCache cache(boolean enabled) {
        CacheProperties props = new CacheProperties();
        props.setEnabled(enabled);
        props.setTtlSeconds(30);
        props.setMaxEntries(16);
        return new CapabilityCache(props);
    }

    private List<CapabilityStat> stats() {
        CapabilityStat stat = mock(CapabilityStat.class);
        return List.of(stat);
    }

    @Test
    @DisplayName("项目隔离：不同 projectId 使用不同缓存键，不串数据")
    void isolatesProjects() {
        when(cases.capabilities(29L)).thenReturn(stats());
        when(cases.capabilities(30L)).thenReturn(List.of());
        CapabilityService service = new CapabilityService(cases, access, cache(true));

        assertThat(service.list(29L)).hasSize(1);
        assertThat(service.list(30L)).isEmpty();
        assertThat(service.list(29L)).hasSize(1);

        verify(cases, times(1)).capabilities(29L);
        verify(cases, times(1)).capabilities(30L);
    }

    @Test
    @DisplayName("写后失效：invalidate 后下一次读回源，不返回陈旧聚合")
    void invalidationRefetches() {
        when(cases.capabilities(29L)).thenReturn(stats(), List.of());
        CapabilityCache cache = cache(true);
        CapabilityService service = new CapabilityService(cases, access, cache);

        assertThat(service.list(29L)).hasSize(1);
        cache.invalidate(29L);
        assertThat(service.list(29L)).isEmpty();

        verify(cases, times(2)).capabilities(29L);
        assertThat(cache.stats().invalidations()).isEqualTo(1);
    }

    @Test
    @DisplayName("权限先于缓存：无权限时不得命中缓存绕过校验")
    void permissionCheckedBeforeCache() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "denied"))
                .when(access).require(any(), any());
        CapabilityService service = new CapabilityService(cases, access, cache(true));

        assertThatThrownBy(() -> service.list(29L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("denied");
        verify(cases, never()).capabilities(anyLong());
    }

    @Test
    @DisplayName("关闭缓存：结果与开启时一致，但每次回源（等价回归）")
    void disabledMatchesEnabled() {
        TestCaseRepository cachedRepo = mock(TestCaseRepository.class);
        TestCaseRepository uncachedRepo = mock(TestCaseRepository.class);
        when(cachedRepo.capabilities(29L)).thenReturn(stats());
        when(uncachedRepo.capabilities(29L)).thenReturn(stats());

        CapabilityService withCache = new CapabilityService(cachedRepo, access, cache(true));
        CapabilityService without = new CapabilityService(uncachedRepo, access, cache(false));

        assertThat(withCache.list(29L)).hasSize(1);
        withCache.list(29L);
        assertThat(without.list(29L)).hasSize(1);
        without.list(29L);

        verify(cachedRepo, times(1)).capabilities(29L);   // 开缓存：只回源一次
        verify(uncachedRepo, times(2)).capabilities(29L); // 关缓存：两次都回源
    }
}
