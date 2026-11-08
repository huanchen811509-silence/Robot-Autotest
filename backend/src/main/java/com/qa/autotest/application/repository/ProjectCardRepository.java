package com.qa.autotest.application.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * 项目卡牌聚合仓储（PRD v2.3 FR-UI-09，UI-02）。
 *
 * <p>只提供「一次查完一批项目」的聚合查询：<b>实现方必须用一条 SQL</b>
 * （{@code project_id IN (...)} 分组）完成，不得在循环里逐个项目查（N+1）。
 * 通过率怎么算留在应用层（{@link com.qa.autotest.application.iam.ProjectCardService}），
 * 这里只回传分子分母。
 */
public interface ProjectCardRepository {

    /**
     * 一批项目的卡牌计数。
     *
     * @param projectIds    需要聚合的项目 id（来自 {@code ProjectService.visible()}，非空）
     * @param windowStart   统计窗口起点，只影响 {@code runCount} 与通过率的分子分母
     * @param viewerUserId  当前用户 id，用于取 {@code myRole}
     * @return 以 project_id 为键的聚合结果；没有数据的项目可能缺席，由调用层补零
     */
    Map<Long, ProjectCardAggregate> aggregate(Collection<Long> projectIds, Instant windowStart,
                                              long viewerUserId);

    /**
     * 单个项目的聚合读数。
     *
     * @param caseCount            用例行数
     * @param runCount             窗口内运行行数（不限状态）
     * @param finishedTotalTests   窗口内已完成运行的 {@code total_tests} 之和（通过率分母）
     * @param finishedPassedTests  窗口内已完成运行的 {@code passed_tests} 之和（通过率分子）
     * @param lastRunAt            最近一条运行的时间，不限窗口
     * @param deviceCount          设备数
     * @param memberCount          成员数
     * @param memberRole           当前用户在该项目的 {@code member_role}，无成员关系为 {@code null}
     */
    record ProjectCardAggregate(
            long caseCount,
            long runCount,
            long finishedTotalTests,
            long finishedPassedTests,
            Instant lastRunAt,
            long deviceCount,
            long memberCount,
            String memberRole) {

        /** 该项目没有任何用例/运行/设备/成员时的零值。 */
        public static final ProjectCardAggregate EMPTY =
                new ProjectCardAggregate(0, 0, 0, 0, null, 0, 0, null);
    }
}
