package com.qa.autotest.domain.project;

import java.util.Locale;

/**
 * 工程类型（v5.2.0 / FR-v5.2-01）。
 *
 * <p>原先 {@code project.kind} 是裸字符串（{@code "standard"} / {@code "platform"}），
 * 校验散在调用方，写错一个字符就变成「未知类型但能存进库」——菜单树、预检分派、
 * 覆盖率口径全都按 kind 分支，静默存进一个拼错的 kind 等于制造一个所有分支都不认的工程。
 *
 * <p>{@link #PYTEST_SUITE} 是 v5.2 新增：一个只承载 pytest 用例库的工程，
 * 它与 {@code standard} 的差别不在存储，而在<b>界面与执行链如何分派</b>
 * （菜单树走工程视图、执行走 pytest 内核、报告只报自动用例）。
 *
 * <p>兼容策略：库里的历史值是 {@code standard} / {@code platform}，两者都保留，
 * 不改数据、不改默认值（默认仍是 {@code standard}）。新增值只加不减。
 */
public enum ProjectKind {

    /** 普通项目：RF 用例为主，菜单走通用视图。 */
    STANDARD("standard"),
    /** 公共库载体（单例）：承载公共用例/资产/模板，界面上不可见、不可删。 */
    PLATFORM("platform"),
    /** pytest 用例库工程：执行走 pytest 内核，菜单走工程视图。 */
    PYTEST_SUITE("pytest-suite");

    private final String code;

    ProjectKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * 解析库值。{@code null} / 空串回落 {@link #STANDARD}（与 {@code project.kind} 的
     * {@code DEFAULT 'standard'} 同源）；未知值抛 {@link IllegalArgumentException}，
     * 由上层转成 {@code INVALID_ARGUMENT}——**不静默回落**，否则拼错的 kind 会被悄悄
     * 存成标准项目，用户以为建了个 pytest 工程，实际跑的是 RF 链路。
     */
    public static ProjectKind of(String raw) {
        if (raw == null || raw.isBlank()) {
            return STANDARD;
        }
        // 容忍下划线写法（pytest_suite）与大小写：前端表单与外部导入常这么写。
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (ProjectKind kind : values()) {
            if (kind.code.equals(normalized)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知的工程类型: " + raw
                + "（可选值: standard / platform / pytest-suite）");
    }

    /** 是否为 pytest 用例库工程。执行链与菜单树都问这一个方法，不各自比字符串。 */
    public static boolean isPytestSuite(String raw) {
        return of(raw) == PYTEST_SUITE;
    }
}
