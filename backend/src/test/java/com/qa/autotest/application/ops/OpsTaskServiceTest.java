package com.qa.autotest.application.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.OpsTaskRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.ops.OpsTask;
import org.junit.jupiter.api.Test;

/** W66-01~03 / W66-17：dry-run 零写入、阶段推进、库名守卫、超时对账。 */
class OpsTaskServiceTest {

    private final OpsTaskRepository repository = mock(OpsTaskRepository.class);
    private final OpsTaskService service = new OpsTaskService(repository);

    @Test
    void dryRunLeavesAuditRowButSkipsBusinessPhases() {
        // v5.16.0 / W66-17 P4：预演也是运维动作，必须留一行「谁预演了什么」。
        // 「零写入」指业务数据零写入（不跑备份 / 不恢复 / 不升级），不是审计零行。
        when(repository.insert(anyLong(), any(), any(), any())).thenReturn(11L);
        OpsTaskService.CreateResult result = service.create(95L, "BACKUP", "{}", null, true);
        assertTrue(result.dryRun());
        assertEquals(11L, result.taskId());
        verify(repository, times(1)).insert(anyLong(), any(), any(), any());
        // 只到 PRECHECK 一步：预演不进入 EXECUTE / VERIFY。
        verify(repository, times(1)).advance(eq(95L), eq(11L), eq("PRECHECK"), anyInt(),
                any(), eq(OpsTask.RUNNING));
        verify(repository).finish(eq(95L), eq(11L), eq(OpsTask.SUCCESS), any());
    }

    @Test
    void realTaskAdvancesThreePhasesThenFinishes() {
        when(repository.insert(anyLong(), any(), any(), any())).thenReturn(7L);
        OpsTaskService.CreateResult result = service.create(95L, "BACKUP", "{}", null, false);
        assertEquals(7L, result.taskId());
        assertEquals(OpsTask.SUCCESS, result.status());
        verify(repository, times(3)).advance(eq(95L), eq(7L), any(), anyInt(), any(),
                eq(OpsTask.RUNNING));
        verify(repository).finish(eq(95L), eq(7L), eq(OpsTask.SUCCESS), any());
    }

    @Test
    void onlyItDatabaseIsAllowedForRestoreDrill() {
        assertEquals("robot_it", OpsTaskService.requireDrillDatabase("robot_it"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> OpsTaskService.requireDrillDatabase("robot_dev"));
        assertTrue(ex.getMessage().contains("robot_dev"));
        assertTrue(ex.getMessage().contains("_it"));
    }

    @Test
    void abortAndSweepGoThroughRepository() {
        when(repository.find(95L, 3L)).thenReturn(java.util.Optional.of(new OpsTask(
                3L, 95L, "BACKUP", null, "{}", "EXECUTE", 50, OpsTask.RUNNING,
                "", null, null, null, null, null)));
        service.abort(95L, 3L);
        verify(repository).finish(eq(95L), eq(3L), eq(OpsTask.ABORTED), any());
        when(repository.sweep(95L)).thenReturn(2);
        assertEquals(2, service.sweep(95L));
    }
}
