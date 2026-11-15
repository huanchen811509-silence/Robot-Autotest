package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v5.11.0 / W61-11：脚本路径的规范化与取名（FR-v5.11-05 判据 4 与 7）。
 *
 * <p>这里的每条断言都对应界面上一个可见后果：relative 决定 tooltip，fileName 决定列内文字，
 * absoluteSource 决定是否加「原始值为绝对路径」的提示。三者错一个，用户看到的就是错的路径。
 */
class CaseScriptViewTest {

    @Test
    @DisplayName("相对路径原样保留，文件名取末段（不剥扩展名）")
    void keepsRelativePath() {
        CaseScriptView view = CaseScriptView.bound("cases/net/roce_bw.py", true, "pytest", 0);

        assertThat(view.scriptPath()).isEqualTo("cases/net/roce_bw.py");
        assertThat(view.scriptName()).isEqualTo("roce_bw.py");
        assertThat(view.bindingState()).isEqualTo(CaseScriptView.BOUND_VERIFIED);
        assertThat(view.absoluteSource()).isFalse();
    }

    @Test
    @DisplayName("未校验的绑定显示 BOUND_UNVERIFIED（不是已校验）")
    void unverifiedBinding() {
        CaseScriptView view = CaseScriptView.bound("tests/test_a.py", false, "pytest", 0);

        assertThat(view.bindingState()).isEqualTo(CaseScriptView.BOUND_UNVERIFIED);
    }

    @Test
    @DisplayName("POSIX 绝对路径从仓库根段截断，且标记 absoluteSource")
    void truncatesPosixAbsolutePath() {
        CaseScriptView view = CaseScriptView.bound("/home/ci/robot-autotest/tests/nic/test_bw.py",
                true, "pytest", 0);

        assertThat(view.scriptPath()).isEqualTo("tests/nic/test_bw.py");
        assertThat(view.scriptName()).isEqualTo("test_bw.py");
        assertThat(view.absoluteSource()).isTrue();
    }

    @Test
    @DisplayName("Windows 绝对路径（反斜杠 + 盘符）同样截断，不含 C: 前缀")
    void truncatesWindowsAbsolutePath() {
        String relative = CaseScriptView.normalizeRelative("C:\\work\\repo\\tests\\nic\\test_c.py");

        assertThat(relative).isEqualTo("tests/nic/test_c.py");
        assertThat(CaseScriptView.isAbsolute("C:\\work\\repo\\tests\\nic\\test_c.py")).isTrue();
    }

    @Test
    @DisplayName("绝对路径里找不到仓库根段：去掉前缀照实返回，仍标记 absoluteSource")
    void keepsTailWhenNoRepoRoot() {
        CaseScriptView view = CaseScriptView.bound("/opt/ci/adhoc.py", false, "pytest", 0);

        assertThat(view.scriptPath()).isEqualTo("opt/ci/adhoc.py");
        assertThat(view.absoluteSource()).isTrue();
    }

    @Test
    @DisplayName("pytest 无绑定 → UNBOUND；robot 无绑定 → NOT_APPLICABLE（RF 无关联列，不猜路径）")
    void emptyStatesDependOnKernel() {
        assertThat(CaseScriptView.empty("pytest").bindingState())
                .isEqualTo(CaseScriptView.UNBOUND);
        assertThat(CaseScriptView.empty("robot").bindingState())
                .isEqualTo(CaseScriptView.NOT_APPLICABLE);
        assertThat(CaseScriptView.empty("robot").scriptPath()).isNull();
    }

    @Test
    @DisplayName("空值与相对路径判定：空串不算绝对路径，也不产生文件名")
    void blankPathsAreSafe() {
        assertThat(CaseScriptView.normalizeRelative("  ")).isNull();
        assertThat(CaseScriptView.fileNameOf(null)).isNull();
        assertThat(CaseScriptView.isAbsolute("")).isFalse();
    }
}
