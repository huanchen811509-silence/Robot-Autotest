package com.qa.autotest.domain.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 工程类型解析（W52-01 / FR-v5.2-01）。
 *
 * <p>关键行为：**未知值必须抛，不能静默回落**。回落会让拼错的 kind 变成一个
 * 「建成功了但所有分支都不认」的工程——菜单树、执行分派、覆盖率口径全按 kind 分支，
 * 这种工程查起来比建失败难十倍。
 */
class ProjectKindTest {

    @Test
    void 空值回落标准项目() {
        assertThat(ProjectKind.of(null)).isEqualTo(ProjectKind.STANDARD);
        assertThat(ProjectKind.of("  ")).isEqualTo(ProjectKind.STANDARD);
    }

    @Test
    void 三个合法值都能解析() {
        assertThat(ProjectKind.of("standard")).isEqualTo(ProjectKind.STANDARD);
        assertThat(ProjectKind.of("platform")).isEqualTo(ProjectKind.PLATFORM);
        assertThat(ProjectKind.of("pytest-suite")).isEqualTo(ProjectKind.PYTEST_SUITE);
        assertThat(ProjectKind.PYTEST_SUITE.code()).isEqualTo("pytest-suite");
    }

    @Test
    void 下划线与大小写都容忍() {
        assertThat(ProjectKind.of("PYTEST_SUITE")).isEqualTo(ProjectKind.PYTEST_SUITE);
        assertThat(ProjectKind.of(" Platform ")).isEqualTo(ProjectKind.PLATFORM);
    }

    @Test
    void 拼错的值直接抛而不是悄悄存成标准项目() {
        assertThatThrownBy(() -> ProjectKind.of("pytest"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知的工程类型");
        assertThatThrownBy(() -> ProjectKind.of("pytest-suit"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pytest-suite");
    }

    @Test
    void isPytestSuite只认枚举不认字符串前缀() {
        assertThat(ProjectKind.isPytestSuite("pytest-suite")).isTrue();
        assertThat(ProjectKind.isPytestSuite("standard")).isFalse();
        // 前缀命中但整体不合法 → 抛，不返回 false。分派点若把「未知」当「不是 pytest」
        // 处理，会静默走到 RF 链路；宁可当场炸。
        assertThatThrownBy(() -> ProjectKind.isPytestSuite("pytest-suite-x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
