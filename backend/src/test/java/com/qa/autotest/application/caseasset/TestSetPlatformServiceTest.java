package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.caseasset.AssetVersion;
import com.qa.autotest.domain.caseasset.TestCase;
import com.qa.autotest.domain.caseasset.TestSet;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * N9 / W57-06：平台用例集库的权限与装配。
 *
 * <p>D-57-04 起读鉴权由「仅管理员」放宽为 case:read，写鉴权放宽为 case:write；
 * 权限判定必须在任何仓储调用之前，否则越权者能从耗时差异里探到集合是否存在。
 */
class TestSetPlatformServiceTest {

    private TestSetRepository sets;
    private AccessControlService access;
    private AssetVersionService assetVersions;
    private TestSetService service;

    @BeforeEach
    void setUp() {
        sets = mock(TestSetRepository.class);
        access = mock(AccessControlService.class);
        assetVersions = mock(AssetVersionService.class);
        service = new TestSetService(sets, mock(TestCaseRepository.class), access,
                mock(AuditService.class), assetVersions);
    }

    @Test
    @DisplayName("无 case:read 权限看不到平台库，且不触发任何仓储读取")
    void 读权限拒绝() {
        doThrow(new BusinessException(ErrorCode.FORBIDDEN, "无权限"))
                .when(access).require(PermissionResource.CASE, PermissionAction.READ);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.platformPage(null, 1, 50));
        assertEquals(ErrorCode.FORBIDDEN, e.errorCode());
        verifyNoInteractions(sets, assetVersions);
        BusinessException items = assertThrows(BusinessException.class,
                () -> service.platformItems(101L));
        assertEquals(ErrorCode.FORBIDDEN, items.errorCode());
    }

    @Test
    @DisplayName("平台库列表装配版本读数与引用方（source=all）")
    void 列表带版本与引用() {
        when(sets.platformPage(null, 1, 50, "all")).thenReturn(
                new PageResult<>(List.of(platformSet(101L, "P-SET-1", 2)), 1L, 1, 50));
        when(assetVersions.platformVersion(101L)).thenReturn(
                new AssetVersionService.VersionView(3, "h3", AssetVersion.DIVERGED,
                        2, "h2", null, true, 3, "h3"));
        when(assetVersions.platformReferences(101L)).thenReturn(List.of(
                ref(11L), ref(12L)));

        PageResult<TestSetService.PlatformSetRow> page = service.platformPage(null, 1, 50);
        TestSetService.PlatformSetRow row = page.items().get(0);
        assertEquals("P-SET-1", row.setCode());
        assertEquals(3, row.assetVersion());
        assertEquals("h3", row.contentHash());
        assertEquals(2, row.referenceCount());
        assertEquals(2, row.references().size());
        assertEquals(1L, page.total());
    }

    @Test
    @DisplayName("平台库成员：集合不存在是 404，空集是合法空列表")
    void 成员只读() {
        when(sets.findPlatform(404L)).thenReturn(Optional.empty());
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.platformItems(404L));
        assertEquals(ErrorCode.NOT_FOUND, e.errorCode());

        when(sets.findPlatform(101L)).thenReturn(Optional.of(platformSet(101L, "P-SET-1", 0)));
        when(sets.platformItems(101L)).thenReturn(List.of());
        assertEquals(List.of(), service.platformItems(101L));
    }

    @Test
    @DisplayName("平台库成员按 seq 顺序透出用例字段")
    void 成员字段() {
        when(sets.findPlatform(101L)).thenReturn(Optional.of(platformSet(101L, "P-SET-1", 1)));
        when(sets.platformItems(101L)).thenReturn(List.of(testCase()));
        List<TestCase> items = service.platformItems(101L);
        assertEquals(1, items.size());
        assertEquals("TC-001", items.get(0).caseCode());
        assertEquals("登录检查", items.get(0).title());
    }

    private static TestSet platformSet(long id, String code, int itemCount) {
        return new TestSet(id, 0L, code, code + " 名称", "平台库", 1L, "ACTIVE", itemCount);
    }

    private static TestCase testCase() {
        return new TestCase(1L, 0L, "TC-001", "登录检查", "FUNC", "P1", "已登录", "首页可达",
                "auth", "APPROVED", "PLATFORM", "login", "平台用例", "LOW");
    }

    private static AssetVersionService.ReferenceView ref(long projectId) {
        return new AssetVersionService.ReferenceView(projectId, "P-" + projectId,
                900L + projectId, 1, AssetVersion.SYNCED, 2, true);
    }
}
