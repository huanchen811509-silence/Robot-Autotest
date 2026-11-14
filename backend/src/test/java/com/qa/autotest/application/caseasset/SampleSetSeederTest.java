package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.repository.TestSetRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** W57-07：样例集播种的纯 Mockito 单测（spy 覆盖数据源查询，不依赖真实 DB）。 */
class SampleSetSeederTest {

    private TestSetRepository sets;
    private SampleSetSeeder seeder;

    @BeforeEach
    void setUp() {
        sets = mock(TestSetRepository.class);
        seeder = spy(new SampleSetSeeder(mock(JdbcTemplate.class), sets));
    }

    @Test
    @DisplayName("0→N 播种：按一级分类建集并加入全部 active 用例")
    void 零到N播种() {
        doReturn(List.of(new SampleSetSeeder.Category(1L, "登录"),
                new SampleSetSeeder.Category(2L, "网络")))
                .when(seeder).loadCategories();
        doReturn(null).when(seeder).findSampleSet(anyString());
        when(sets.insertPlatform(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(10L, 11L);
        when(sets.platformCategoryCaseIds(anyLong(), isNull()))
                .thenReturn(List.of(100L, 101L));
        when(sets.platformItemIds(anyLong())).thenReturn(List.of());
        when(sets.nextPlatformSeqNo(anyLong())).thenReturn(0);
        when(sets.addPlatformItemIfAbsent(anyLong(), anyLong(), anyInt(), any()))
                .thenReturn(1);

        SampleSetSeeder.SeedResult result = seeder.ensureSamples();
        assertEquals(2, result.created());
        assertEquals(0, result.skipped());
        assertEquals(4, result.addedItems());
        verify(sets, times(2))
                .insertPlatform(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("二次幂等：已存在的样例集不重复建、不重复加成员")
    void 二次幂等() {
        doReturn(List.of(new SampleSetSeeder.Category(1L, "登录")))
                .when(seeder).loadCategories();
        doReturn(new SampleSetSeeder.ExistingSample(10L, false))
                .when(seeder).findSampleSet(anyString());

        SampleSetSeeder.SeedResult result = seeder.ensureSamples();
        assertEquals(0, result.created());
        assertEquals(1, result.skipped());
        assertEquals(0, result.addedItems());
        verify(sets, never())
                .insertPlatform(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("user_modified=1：播种与重建都跳过，不覆盖用户成员")
    void 用户接管跳过() {
        doReturn(List.of(new SampleSetSeeder.Category(1L, "登录")))
                .when(seeder).loadCategories();
        doReturn(new SampleSetSeeder.ExistingSample(10L, true))
                .when(seeder).findSampleSet(anyString());

        SampleSetSeeder.SeedResult result = seeder.rebuild();
        assertEquals(0, result.created());
        assertEquals(1, result.skipped());
        assertEquals(0, result.addedItems());
        verify(sets, never())
                .addPlatformItemIfAbsent(anyLong(), anyLong(), anyInt(), any());
    }

    @Test
    @DisplayName("重建计数：已存在集补齐缺失成员（已有成员跳过）")
    void 重建计数() {
        doReturn(List.of(new SampleSetSeeder.Category(1L, "登录")))
                .when(seeder).loadCategories();
        doReturn(new SampleSetSeeder.ExistingSample(10L, false))
                .when(seeder).findSampleSet(anyString());
        when(sets.platformCategoryCaseIds(anyLong(), isNull()))
                .thenReturn(List.of(100L, 101L, 102L));
        when(sets.platformItemIds(10L)).thenReturn(List.of(100L));
        when(sets.nextPlatformSeqNo(10L)).thenReturn(1);
        when(sets.addPlatformItemIfAbsent(anyLong(), anyLong(), anyInt(), any()))
                .thenReturn(1);

        SampleSetSeeder.SeedResult result = seeder.rebuild();
        assertEquals(0, result.created());
        assertEquals(1, result.skipped());
        assertEquals(2, result.addedItems());
    }
}
