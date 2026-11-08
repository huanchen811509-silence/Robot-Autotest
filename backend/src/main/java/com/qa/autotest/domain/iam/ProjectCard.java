package com.qa.autotest.domain.iam;

import java.time.Instant;

/**
 * 项目卡牌（PRD v2.3 FR-UI-09 / FR-UI-11）。
 *
 * <p>机读字段，不含任何展示文案：缺数据统一由前端翻成「—」，接口不吐「暂无」之类的中文，
 * 否则英文界面必然冒中文（与 {@code ProjectOverview} 同一条纪律）。
 *
 * <p>{@code isCurrent} 由调用方回传的 {@code current} 比较得出——当前项目是前端 localStorage
 * 状态，服务端无从得知，不能靠请求头猜。
 *
 * @param caseCount   用例数（{@code test_case} 行数）
 * @param runCount    窗口内运行数（含失败/取消，回答「跑过几次」）
 * @param passRateBp  窗口内已完成运行的用例级通过率，万分位；<b>没有分母时为 {@code null}</b>
 * @param lastRunAt   最近一次运行时间，<b>不限窗口</b>；从未运行为 {@code null}
 * @param myRole      当前用户在该项目的成员角色；全局 admin 无成员关系时为 {@code admin}
 */
public record ProjectCard(
        Long id,
        String code,
        String name,
        String description,
        boolean enabled,
        String visibility,
        Long ownerId,
        boolean isCurrent,
        long caseCount,
        long runCount,
        Integer passRateBp,
        Instant lastRunAt,
        long deviceCount,
        long memberCount,
        String myRole) {
}
