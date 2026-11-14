package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.BusinessException;
import java.lang.reflect.Constructor;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** W57-19：只读预览、条数=可执行数、跳过原因、digest 前 12 位。 */
class CommandPreviewServiceTest {

    private static final long PROJECT = 1L;

    private CommandPlanProvider robot;
    private CommandPlanProvider pytest;
    private CommandPreviewService service;

    private static CommandPlan plan(String code, String arg) {
        return new CommandPlan(code, List.of("robot", arg), "/opt/ws", 3600, null);
    }

    @BeforeEach
    void setUp() {
        robot = mock(CommandPlanProvider.class);
        pytest = mock(CommandPlanProvider.class);
        when(robot.kernel()).thenReturn("robot");
        when(pytest.kernel()).thenReturn("pytest");
        AccessControlService access = mock(AccessControlService.class);
        service = new CommandPreviewService(List.of(robot, pytest), access);
    }

    @Test
    @DisplayName("预览条数 = 可执行命令数，requested 为请求数")
    void 条数等于可执行数() {
        when(robot.plans(PROJECT, List.of(1L, 2L, 3L)))
                .thenReturn(List.of(plan("C1", "a"), plan("C2", "b")));
        CommandPreviewService.PreviewResult result =
                service.preview(PROJECT, "robot", List.of(1L, 2L, 3L));
        assertEquals(3, result.requested());
        assertEquals(2, result.executable());
        assertEquals(2, result.items().size());
    }

    @Test
    @DisplayName("缺绑定的用例标注跳过原因，且不编造命令")
    void 跳过原因() {
        when(robot.plans(PROJECT, List.of(1L, 2L))).thenReturn(List.of(plan("C1", "a")));
        CommandPreviewService.PreviewResult result = service.preview(PROJECT, "robot", List.of(1L, 2L));
        assertEquals(1, result.executable());
        assertFalse(result.skipReasons().isEmpty());
        assertTrue(result.skipReasons().get(0).contains("不编造命令"));
    }

    @Test
    @DisplayName("digest12 为整份清单摘要的前 12 位")
    void 短摘要一致() {
        List<CommandPlan> plans = List.of(plan("C1", "a"), plan("C2", "b"));
        when(robot.plans(PROJECT, List.of(1L, 2L))).thenReturn(plans);
        CommandPreviewService.PreviewResult result = service.preview(PROJECT, "robot", List.of(1L, 2L));
        assertEquals(CommandDigest.digest(plans).substring(0, 12), result.digest12());
        assertEquals(12, result.digest12().length());
        assertEquals(CommandDigest.digest(plans), result.digest());
    }

    @Test
    @DisplayName("预览不写库：唯一公开构造器不含任何写仓储 / run 服务依赖，且调用幂等")
    void 预览不写库() {
        Constructor<?>[] constructors = CommandPreviewService.class.getConstructors();
        assertEquals(1, constructors.length);
        for (Class<?> type : constructors[0].getParameterTypes()) {
            String name = type.getName();
            assertFalse(name.contains("Jdbc"), "不应持有 JdbcTemplate");
            assertFalse(name.contains("Store"), "不应持有写 Store");
            assertFalse(name.contains("RunService"), "不应持有 run 服务");
            assertFalse(name.contains("Confirmation"), "不应持有确认服务");
        }
        List<CommandPlan> plans = List.of(plan("C1", "a"));
        when(robot.plans(PROJECT, List.of(1L))).thenReturn(plans);
        CommandPreviewService.PreviewResult a = service.preview(PROJECT, "robot", List.of(1L));
        CommandPreviewService.PreviewResult b = service.preview(PROJECT, "robot", List.of(1L));
        assertEquals(a.digest(), b.digest());
        verify(robot, times(2)).plans(PROJECT, List.of(1L));
        verify(pytest, never()).plans(anyLong(), anyList());
    }

    @Test
    @DisplayName("未知 kernel 直接 400")
    void 未知内核() {
        assertThrows(BusinessException.class,
                () -> service.preview(PROJECT, "bogus", List.of(1L)));
    }

    @Test
    @DisplayName("空 caseIds：可执行 0 条，digest 仍稳定且不抛错")
    void 空选择() {
        when(robot.plans(PROJECT, List.of())).thenReturn(List.of());
        CommandPreviewService.PreviewResult result = service.preview(PROJECT, "ROBOT", List.of());
        assertEquals(0, result.requested());
        assertEquals(0, result.executable());
        assertEquals(64, result.digest().length());
    }

    @Test
    @DisplayName("只调用匹配 kernel 的 provider，另一个完全不触发")
    void 内核路由() {
        when(robot.plans(PROJECT, List.of(9L))).thenReturn(List.of(plan("C9", "z")));
        service.preview(PROJECT, "robot", List.of(9L));
        verify(robot, times(1)).plans(PROJECT, List.of(9L));
        verify(pytest, never()).plans(anyLong(), anyList());
    }
}
