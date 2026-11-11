package com.qa.autotest.application.execution;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** EXEC-06：完整日志定位与下载前的存在性校验。 */
class RunLogFileServiceTest {

    @TempDir
    Path runDir;

    private final RunLogFileService service = new RunLogFileService();

    @Test
    @DisplayName("没有产物目录：404，消息带 runId")
    void 无产物目录() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.locate(record("RUN-9", null)));

        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
        assertTrue(ex.getMessage().contains("RUN-9"), ex.getMessage());
    }

    @Test
    @DisplayName("产物目录里没有 run.log：404，消息带期望路径")
    void 日志文件缺失() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.locate(record("RUN-8", runDir.toString())));

        assertEquals(ErrorCode.NOT_FOUND, ex.errorCode());
        assertTrue(ex.getMessage().contains(RunLogFileService.LOG_FILE), ex.getMessage());
        assertTrue(ex.getMessage().contains(runDir.toString()), ex.getMessage());
    }

    @Test
    @DisplayName("run.log 存在：定位到该文件，下载资源可读")
    void 日志文件存在() throws IOException {
        Path log = runDir.resolve(RunLogFileService.LOG_FILE);
        Files.writeString(log, "line1\nline2\n");

        assertEquals(log, service.locate(record("RUN-7", runDir.toString())));
        assertTrue(service.download(record("RUN-7", runDir.toString())) != null);
    }

    private static RunRecord record(String runId, String artifactsDir) {
        return new RunRecord(1L, runId, 7L, null, RunState.FINISHED, 0, 1, 0, 0, 0, 0,
                null, null, null, artifactsDir, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, null);
    }
}
