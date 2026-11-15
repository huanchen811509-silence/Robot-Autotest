package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v5.10.0 / FR-v5.10-08 / W60-02：项目编号取号。
 *
 * <p>两条判据各自对应一个真实故障：
 * <ul>
 *   <li><b>只增不减</b>：旧实现按 {@code MAX(序号)+1} 取号，删掉最大号后再建会拿回同一个号。
 *       现在号段由序列承担，取号与「该编码是否还存在」无关。</li>
 *   <li><b>撞号封顶</b>：旧实现重试 100 次。撞号只可能来自同 scope 下有人手工写过编码，
 *       100 次只是把一次必然失败拖成 100 次数据库往返；封到 3 次并给 409 人话。</li>
 * </ul>
 */
class ProjectCodeSequenceTest {

    private ProjectRepository projects;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectRepository.class);
        service = new ProjectService(projects, mock(UserRepository.class),
                mock(AccessControlService.class), mock(AuditService.class));
        TenantContext.set(1L, "tester", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("取号只增不减：序列给 007 就用 007，不因库里没有 001~006 而回退")
    void 序列只增不减() {
        when(projects.nextCodeSequence(anyString())).thenReturn(7L);
        when(projects.codeExists(anyString(), any())).thenReturn(false);

        String code = service.nextCode("prj", "robot");

        assertThat(code).matches("PRJ-RF-\\d{6}-007");
        // 只向序列要了一次号：没有「从 001 开始试」的循环
        verify(projects, times(1)).nextCodeSequence(anyString());
    }

    @Test
    @DisplayName("连续撞号 → 409 人话，且只尝试 3 次（不再跑 100 次）")
    void 撞号封顶() {
        when(projects.nextCodeSequence(anyString())).thenReturn(1L);
        when(projects.codeExists(anyString(), any())).thenReturn(true);

        BusinessException ex = catchThrowableOfType(() -> service.nextCode("PRJ", "robot"),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(ex.getMessage()).contains("编号生成冲突");
        // 不得返回 500：CONFLICT 就是 409
        verify(projects, times(3)).nextCodeSequence(anyString());
    }

    @Test
    @DisplayName("中间撞号一次、第二次拿到空号 → 正常返回，取号 2 次")
    void 撞一次后成功() {
        when(projects.nextCodeSequence(anyString())).thenReturn(1L, 2L);
        when(projects.codeExists(anyString(), any())).thenReturn(true, false);

        String code = service.nextCode("PRJ", "robot");

        assertThat(code).endsWith("-002");
        verify(projects, times(2)).nextCodeSequence(anyString());
    }
}
