package com.qa.autotest.application.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 失败归因五层（W52-09 / FR-v5.2-06）。
 *
 * <p>重点是**顺序**与**确定性**：环境类关键字必须先于断言判定，
 * 否则「连不上 SSH 导致断言抛错」会被记成产品缺陷，把环境失败混进产品通过率。
 */
class FailureAttributorTest {

    @Test
    void 环境类先于断言判定() {
        // 真实形态：连接失败后断言里也会出现 assert 字样，顺序判错就归错类。
        FailureAttributor.Attribution a = FailureAttributor.of("pytest", "FAIL",
                "AssertionError: 读取失败\nparamiko.ssh_exception.NoValidConnectionsError: "
                        + "Unable to connect to port 22", List.of("读取链路状态"));

        assertThat(a.category()).isEqualTo(FailureAttributor.CATEGORY_ENV);
        assertThat(a.environmentSide()).isTrue();
        assertThat(a.suggestion()).contains("设备侧");
    }

    @Test
    void SSH认证失败归环境并给出可达性建议() {
        FailureAttributor.Attribution a = FailureAttributor.of("pytest", "ERROR",
                "paramiko.ssh_exception.AuthenticationException: Authentication failed.",
                List.of());

        assertThat(a.category()).isEqualTo(FailureAttributor.CATEGORY_ENV);
        assertThat(a.conclusion()).isEqualTo(FailureAttributor.CONCLUSION_ERROR);
    }

    @Test
    void 不认识参数归配置契约不是产品缺陷() {
        FailureAttributor.Attribution a = FailureAttributor.of("pytest", "ERROR",
                "ERROR: usage: pytest [options]\nerror: unrecognized arguments: --json-report",
                List.of());

        assertThat(a.category()).isEqualTo(FailureAttributor.CATEGORY_CONFIG);
        assertThat(a.environmentSide()).isTrue();
        assertThat(a.suggestion()).contains("契约");
    }

    @Test
    void 语法错误归脚本自身() {
        FailureAttributor.Attribution a = FailureAttributor.of("pytest", "ERROR",
                "SyntaxError: invalid syntax (test_im_net_131.py, line 42)", List.of());

        assertThat(a.category()).isEqualTo(FailureAttributor.CATEGORY_SCRIPT);
        assertThat(a.location()).isEqualTo("test_im_net_131.py:42");
    }

    @Test
    void 规格数据缺失归数据侧() {
        FailureAttributor.Attribution a = FailureAttributor.of("pytest", "FAIL",
                "KeyError: 'lnkcap'（用例明确要求的字段未有效读到）", List.of("读取链路状态：LnkCap=None"));

        assertThat(a.category()).isEqualTo(FailureAttributor.CATEGORY_DATA);
        assertThat(a.location()).isEqualTo("读取链路状态：LnkCap=None");
    }

    @Test
    void 判定不通过归断言并保留原文与失败步() {
        FailureAttributor.Attribution a = FailureAttributor.of("pytest", "FAIL",
                "校验速率：期望 16GT/s，实测 8GT/s", List.of("读取链路状态：LnkSta=8GT/s x8"));

        assertThat(a.category()).isEqualTo(FailureAttributor.CATEGORY_ASSERT);
        assertThat(a.conclusion()).isEqualTo(FailureAttributor.CONCLUSION_FAIL);
        assertThat(a.original()).isEqualTo("校验速率：期望 16GT/s，实测 8GT/s");
        assertThat(a.evidenceStep()).isEqualTo("读取链路状态：LnkSta=8GT/s x8");
        assertThat(a.environmentSide()).isFalse();
    }

    @Test
    void Plan_only的skip不算失败也不进环境分母() {
        FailureAttributor.Attribution a = FailureAttributor.of("pytest", "SKIP",
                "Plan-only：AW 未真实执行，跳过功能断言", List.of("步骤1：Plan-only 无真实回显"));

        assertThat(a.category()).isEqualTo(FailureAttributor.CATEGORY_PLAN_ONLY);
        assertThat(a.environmentSide()).isFalse();
        assertThat(a.suggestion()).contains("real 模式");
    }

    @Test
    void 通过的用例不产出建议() {
        FailureAttributor.Attribution a = FailureAttributor.of("robot", "PASS", null, List.of());

        assertThat(a.conclusion()).isEqualTo(FailureAttributor.CONCLUSION_PASS);
        assertThat(a.suggestion()).isEmpty();
        assertThat(a.original()).isEmpty();
    }

    @Test
    void 结论缺失按ERROR不按PASS() {
        assertThat(FailureAttributor.of("pytest", null, "boom", List.of()).conclusion())
                .isEqualTo(FailureAttributor.CONCLUSION_ERROR);
        assertThat(FailureAttributor.of("pytest", "  ", null, List.of()).conclusion())
                .isEqualTo(FailureAttributor.CONCLUSION_ERROR);
    }

    @Test
    void 文本毫不相关时归未知并如实说明() {
        FailureAttributor.Attribution a = FailureAttributor.of("pytest", "FAIL", "???", List.of());

        assertThat(a.category()).isEqualTo(FailureAttributor.CATEGORY_UNKNOWN);
        assertThat(a.suggestion()).contains("人工判断");
    }

    @Test
    void 同一输入两次调用结果逐字段一致() {
        FailureAttributor.Attribution first = FailureAttributor.of("pytest", "FAIL",
                "断言失败：期望 100，实测 99", List.of("步骤2：值 99"));
        FailureAttributor.Attribution second = FailureAttributor.of("pytest", "FAIL",
                "断言失败：期望 100，实测 99", List.of("步骤2：值 99"));

        assertThat(first).isEqualTo(second);
    }
}
