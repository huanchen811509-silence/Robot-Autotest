package com.qa.autotest.application.caseasset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.caseasset.TestSet;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v5.32.0 / FR-v5.32-02：公共库「建集并加成员」一次提交。
 *
 * <p>验收点在「不留半成品」与「序号不重号」两件事上：
 * 先 create 再 addItems 的老路径在第二步失败时留下空集，用户得回列表删掉重来；
 * 序号若从 0 重开，成员在集合里的顺序会与既有成员打架。
 */
class PlatformCaseSetCreateWithItemsTest {

    private final Map<Long, TestSet> platformSets = new HashMap<>();
    private final List<Long> added = new ArrayList<>();
    private final Set<Long> existingMembers = new HashSet<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, "admin", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("建集 + 加成员：编号/名称落库，成员条数与跳过数如实返回")
    void createWithItems() {
        TestSetService service = service();

        TestSetService.PlatformSetWithItems result =
                service.createPlatformWithItems("PS-NEW", "冒烟集", "建集即入集",
                        List.of(101L, 102L, 103L));

        assertEquals("PS-NEW", result.set().setCode());
        assertEquals(3, result.added());
        assertEquals(0, result.skipped());
        assertEquals(List.of(101L, 102L, 103L), added);
    }

    @Test
    @DisplayName("重复成员计入 skipped，不重复插入")
    void skipDuplicates() {
        existingMembers.add(101L);
        TestSetService service = service();

        TestSetService.PlatformSetWithItems result =
                service.createPlatformWithItems("PS-DUP", "去重集", null, List.of(101L, 102L));

        assertEquals(1, result.added());
        assertEquals(1, result.skipped());
        assertEquals(List.of(102L), added, "101 已在集合里，不得再插一次");
    }

    @Test
    @DisplayName("空 caseIds 等价于只建空集，不报错")
    void emptyCaseIds() {
        TestSetService service = service();

        TestSetService.PlatformSetWithItems result =
                service.createPlatformWithItems("PS-EMPTY", "空集", null, null);

        assertEquals(0, result.added());
        assertEquals(0, result.skipped());
        assertTrue(added.isEmpty());
    }

    @Test
    @DisplayName("编号重复在建集阶段就 409，不会先写成员")
    void duplicateCodeRejected() {
        platformSets.put(7L, new TestSet(7L, null, "PS-TAKEN", "已存在", null, 1L, "ACTIVE", 0));
        TestSetService service = service();

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createPlatformWithItems("PS-TAKEN", "重名集", null, List.of(101L)));

        assertEquals(ErrorCode.CONFLICT, ex.errorCode());
        assertTrue(added.isEmpty(), "建集失败时不得写入任何成员");
    }

    // ---------------------------------------------------------------- 夹具

    private TestSetService service() {
        return new TestSetService(testSetRepo(), emptyCaseRepo(), allowAllAccess(), noopAudit(), null);
    }

    private TestSetRepository testSetRepo() {
        return (TestSetRepository) Proxy.newProxyInstance(
                TestSetRepository.class.getClassLoader(), new Class<?>[]{TestSetRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "platformCodeExists" -> {
                        String code = (String) args[0];
                        yield platformSets.values().stream()
                                .anyMatch(s -> s.setCode().equals(code));
                    }
                    case "insertPlatform" -> {
                        String code = (String) args[0];
                        long id = platformSets.size() + 100L;
                        platformSets.put(id, new TestSet(id, null, code, (String) args[1],
                                (String) args[2], 1L, "ACTIVE", 0));
                        yield id;
                    }
                    case "findPlatform" -> Optional.ofNullable(platformSets.get((Long) args[0]));
                    case "platformItemIds" -> new ArrayList<>(existingMembers);
                    case "nextPlatformSeqNo" -> existingMembers.size();
                    case "addPlatformItemIfAbsent" -> {
                        long caseId = (Long) args[1];
                        added.add(caseId);
                        existingMembers.add(caseId);
                        yield 1;
                    }
                    case "markPlatformUserModified" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private TestCaseRepository emptyCaseRepo() {
        return (TestCaseRepository) Proxy.newProxyInstance(
                TestCaseRepository.class.getClassLoader(), new Class<?>[]{TestCaseRepository.class},
                (proxy, method, args) -> defaultValue(method.getReturnType()));
    }

    private AccessControlService allowAllAccess() {
        // AccessControlService 是具体类（不是接口），Proxy 用不了；Mockito 的空实现即放行。
        return org.mockito.Mockito.mock(AccessControlService.class);
    }

    private AuditService noopAudit() {
        return org.mockito.Mockito.mock(AuditService.class);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }
}
