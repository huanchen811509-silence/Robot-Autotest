package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FR-READY-01/02/10/11：bindingState 四态过滤的 SQL 片段生成。
 *
 * <p>每条断言对应一个可观察后果：拼出的 WHERE 决定分页前 total 与结果，
 * 拼错一个状态，用户筛「未绑定」就会看到已绑定的用例。
 */
class BindingStateFilterTest {

    private static String sql(StringBuilder where) {
        return where.toString();
    }

    @Test
    @DisplayName("四态白名单：null/空合法，未知值拒绝")
    void whitelist() {
        assertThat(BindingStateFilter.isValid(null)).isTrue();
        assertThat(BindingStateFilter.isValid("")).isTrue();
        assertThat(BindingStateFilter.isValid("  ")).isTrue();
        assertThat(BindingStateFilter.isValid("BOUND_VERIFIED")).isTrue();
        assertThat(BindingStateFilter.isValid("BOUND_UNVERIFIED")).isTrue();
        assertThat(BindingStateFilter.isValid("UNBOUND")).isTrue();
        assertThat(BindingStateFilter.isValid("NOT_APPLICABLE")).isTrue();
        assertThat(BindingStateFilter.isValid("BOGUS")).isFalse();
    }

    @Test
    @DisplayName("null/空 bindingState 不追加任何条件（默认无回归）")
    void noFilterWhenBlank() {
        StringBuilder where = new StringBuilder(" WHERE tc.project_id = ?");
        List<Object> args = new ArrayList<>();
        BindingStateFilter.apply(where, args, 1L, "pytest", null, "tc.id");
        BindingStateFilter.apply(where, args, 1L, "pytest", "  ", "tc.id");

        assertThat(sql(where)).isEqualTo(" WHERE tc.project_id = ?");
        assertThat(args).isEmpty();
    }

    @Test
    @DisplayName("pytest 内核：BOUND_VERIFIED 生成 EXISTS + verified=1，参数按序入列")
    void boundVerifiedPytest() {
        StringBuilder where = new StringBuilder(" WHERE tc.project_id = ?");
        List<Object> args = new ArrayList<>();
        BindingStateFilter.apply(where, args, 42L, "pytest", "BOUND_VERIFIED", "tc.id");

        assertThat(sql(where)).contains("EXISTS")
                .contains("b.case_id = tc.id")
                .contains("b.verified = 1");
        assertThat(args).containsExactly(42L, "pytest");
    }

    @Test
    @DisplayName("pytest 内核：BOUND_UNVERIFIED 用 verified=0")
    void boundUnverifiedPytest() {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        BindingStateFilter.apply(where, args, 7L, "pytest", "BOUND_UNVERIFIED", "tc.id");

        assertThat(sql(where)).contains("b.verified = 0").doesNotContain("b.verified = 1");
        assertThat(args).containsExactly(7L, "pytest");
    }

    @Test
    @DisplayName("pytest 内核：UNBOUND 用 NOT EXISTS，不下发 verified 条件")
    void unboundPytest() {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        BindingStateFilter.apply(where, args, 9L, "pytest", "UNBOUND", "tc.id");

        assertThat(sql(where)).contains("NOT EXISTS").doesNotContain("b.verified");
        assertThat(args).containsExactly(9L, "pytest");
    }

    @Test
    @DisplayName("pytest 内核：NOT_APPLICABLE 恒 0")
    void notApplicablePytest() {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        BindingStateFilter.apply(where, args, 9L, "pytest", "NOT_APPLICABLE", "tc.id");

        assertThat(sql(where)).contains(" AND 1 = 0");
        assertThat(args).isEmpty();
    }

    @Test
    @DisplayName("robot 内核：UNBOUND/BOUND_* 恒 0，NOT_APPLICABLE 全部（含 platform 公共库）")
    void robotKernelSemantics() {
        StringBuilder unbound = new StringBuilder(" WHERE 1=1");
        BindingStateFilter.apply(unbound, new ArrayList<>(), 100L, "robot", "UNBOUND", "id");
        assertThat(sql(unbound)).contains(" AND 1 = 0");

        StringBuilder verified = new StringBuilder(" WHERE 1=1");
        BindingStateFilter.apply(verified, new ArrayList<>(), 100L, "robot", "BOUND_VERIFIED", "id");
        assertThat(sql(verified)).contains(" AND 1 = 0");

        StringBuilder notApplicable = new StringBuilder(" WHERE 1=1");
        BindingStateFilter.apply(notApplicable, new ArrayList<>(), 100L, "robot", "NOT_APPLICABLE", "id");
        assertThat(sql(notApplicable)).contains(" AND 1 = 1");
    }

    @Test
    @DisplayName("非法状态抛异常（不静默降级，防筛出错数据）")
    void rejectsIllegalState() {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        assertThatThrownBy(() -> BindingStateFilter.apply(
                where, new ArrayList<>(), 1L, "pytest", "BOGUS", "tc.id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BOGUS");
    }

    @Test
    @DisplayName("effectiveState：不支持脚本内核一律归一为 NOT_APPLICABLE")
    void effectiveState() {
        assertThat(BindingStateFilter.effectiveState("robot", null))
                .isEqualTo(CaseScriptView.NOT_APPLICABLE);
        assertThat(BindingStateFilter.effectiveState("pytest", null))
                .isEqualTo(CaseScriptView.UNBOUND);
        assertThat(BindingStateFilter.effectiveState("pytest", "BOUND_VERIFIED"))
                .isEqualTo(CaseScriptView.BOUND_VERIFIED);
    }
}
