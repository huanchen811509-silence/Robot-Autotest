package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.autotest.common.BusinessException;
import org.junit.jupiter.api.Test;

/**
 * 项目测试内核归一（v2.6.1 / V26-08）。
 *
 * <p>大师指令：「每个项目都要支持显示测试内核 是pytest 还是 robot Framework」。
 * `normalizeKernel` 是 create / update 的共同入口，锁四件事：空值回 robot、
 * 两个合法值原样通过（含大小写）、未知值 400、不是四不像。
 */
class ProjectServiceKernelTest {

    @Test
    void 空值回默认robot() {
        assertThat(ProjectService.normalizeKernel(null)).isEqualTo("robot");
        assertThat(ProjectService.normalizeKernel("")).isEqualTo("robot");
        assertThat(ProjectService.normalizeKernel("   ")).isEqualTo("robot");
    }

    @Test
    void 合法值原样通过且大小写不敏感() {
        assertThat(ProjectService.normalizeKernel("robot")).isEqualTo("robot");
        assertThat(ProjectService.normalizeKernel("pytest")).isEqualTo("pytest");
        assertThat(ProjectService.normalizeKernel(" PyTest ")).isEqualTo("pytest");
        assertThat(ProjectService.normalizeKernel("ROBOT")).isEqualTo("robot");
    }

    @Test
    void 未知内核拒绝并给出可选值() {
        assertThatThrownBy(() -> ProjectService.normalizeKernel("junit"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("junit")
                .hasMessageContaining("robot / pytest");
    }

    @Test
    void Project记录的兼容构造缺省也是robot() {
        var project = new com.qa.autotest.domain.iam.Project(
                1L, "k", "内核缺省", null, true, null, "private");
        assertThat(project.testKernel()).isEqualTo("robot");
    }
}
