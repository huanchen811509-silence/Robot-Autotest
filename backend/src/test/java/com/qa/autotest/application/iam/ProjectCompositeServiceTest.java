package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.asset.GlobalAssetService;
import com.qa.autotest.application.group.GroupService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.Project;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v5.10.0 / FR-v5.10-10 / W60-07：向导第 8 步一次性提交。
 *
 * <p>四个用例对应四条真实故障面：①六类对象是否都真的落了；②一个成员被停用会不会把整个项目拖死；
 * ③非管理员能不能靠向导绕过组授权判权；④凭证绑定（写的是平台级设备）失败会不会让项目建不出来。
 */
class ProjectCompositeServiceTest {

    private ProjectService projects;
    private ProjectTemplateService templates;
    private GroupService groups;
    private GlobalAssetService assets;
    private AccessControlService access;
    private ProjectCompositeService service;

    private static final long PID = 77L;

    @BeforeEach
    void setUp() {
        projects = mock(ProjectService.class);
        templates = mock(ProjectTemplateService.class);
        groups = mock(GroupService.class);
        assets = mock(GlobalAssetService.class);
        access = mock(AccessControlService.class);
        service = new ProjectCompositeService(projects, templates, groups, assets, access);

        when(projects.create(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new Project(77L, "WIZ-1", "向导项目", null, true, 1L, "private",
                        "robot", "standard", null));
    }

    private static ProjectCompositeService.CompositeRequest full() {
        return new ProjectCompositeService.CompositeRequest(
                "WIZ-1", "向导项目", "说明", "private", "robot", "standard", null, true,
                List.of(new ProjectCompositeService.MemberSpec(2L, List.of("engineer"))),
                List.of(new ProjectCompositeService.GroupSpec(5L, "viewer")),
                List.of(new ProjectCompositeService.AssetSpec("DEVICE", 9L, 100L, null)),
                List.of(11L, 12L), List.of(21L), "向导用例集");
    }

    @Test
    @DisplayName("六类对象一次提交：项目/成员/组授权/内容/资产逐类落地")
    void 全部落地() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.addMembers(eq(PID), anyList()))
                .thenReturn(new ProjectService.BatchMembersResult(1, 0, List.of()));
        when(templates.applyContent(eq(PID), any()))
                .thenReturn(new ProjectTemplateService.ContentResult(31L, 3, 1));

        ProjectCompositeService.CompositeResult result = service.create(full());

        assertThat(result.projectId()).isEqualTo(PID);
        assertThat(result.members()).isEqualTo(1);
        assertThat(result.groups()).isEqualTo(1);
        assertThat(result.cases()).isEqualTo(3);
        assertThat(result.assets()).isEqualTo(1);
        assertThat(result.warnings()).isEmpty();
        verify(groups).grant(PID, 5L, "viewer");
        verify(assets).bindDeviceCredentials(9L, 100L, null);
    }

    @Test
    @DisplayName("有成员被停用：不回滚项目，失败原因进 warnings")
    void 成员失败降级为警告() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.addMembers(eq(PID), anyList()))
                .thenReturn(new ProjectService.BatchMembersResult(0, 1,
                        List.of(new ProjectService.MemberFailure(2L, "用户已停用"))));
        when(templates.applyContent(eq(PID), any()))
                .thenReturn(new ProjectTemplateService.ContentResult(31L, 3, 1));

        ProjectCompositeService.CompositeResult result = service.create(full());

        assertThat(result.projectId()).isEqualTo(PID);
        assertThat(result.members()).isZero();
        assertThat(result.warnings()).anyMatch(w -> w.contains("2") && w.contains("用户已停用"));
    }

    @Test
    @DisplayName("非管理员带上组授权 → 403，且不写任何组")
    void 非管理员组授权被拒() {
        when(access.isAdmin()).thenReturn(false);

        BusinessException ex = catchThrowableOfType(() -> service.create(full()),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
        verify(groups, never()).grant(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("凭证绑定失败（无全局权限）→ 项目仍然建出来，只记警告")
    void 凭证绑定失败降级() {
        when(access.isAdmin()).thenReturn(true);
        when(projects.addMembers(eq(PID), anyList()))
                .thenReturn(new ProjectService.BatchMembersResult(1, 0, List.of()));
        when(templates.applyContent(eq(PID), any()))
                .thenReturn(new ProjectTemplateService.ContentResult(31L, 3, 1));
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可进入全局模式"))
                .when(assets).bindDeviceCredentials(anyLong(), any(), any());

        ProjectCompositeService.CompositeResult result = service.create(full());

        assertThat(result.projectId()).isEqualTo(PID);
        assertThat(result.warnings()).anyMatch(w -> w.contains("凭证未绑定"));
    }

    @Test
    @DisplayName("空请求 → 400，不落库")
    void 空请求被拒() {
        BusinessException ex = catchThrowableOfType(() -> service.create(null),
                BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
        verify(projects, never()).create(any(), any(), any(), any(), any(), any(), any(),
                anyBoolean());
    }
}
