package com.qa.autotest.application.execution;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 并发准入：三层上限、终态释放、幂等与背压（FR-3.4-02）。 */
class RunAdmissionServiceTest {

    private RunAdmissionService service;

    @BeforeEach
    void setUp() {
        service = new RunAdmissionService();
        ReflectionTestUtils.setField(service, "maxInflight", 2);
        ReflectionTestUtils.setField(service, "maxPerProject", 2);
        ReflectionTestUtils.setField(service, "maxPerNode", 2);
        ReflectionTestUtils.setField(service, "maxWaitMs", 0L);
    }

    @Test
    void rejectsWhenGlobalCapReachedAndReleasesOnFinish() {
        service.admit(1L, 10L, "RUN-1");
        service.admit(1L, 10L, "RUN-2");
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.admit(1L, 10L, "RUN-3"));
        assertEquals(ErrorCode.CONFLICT, rejected.errorCode());

        service.release("RUN-1");
        assertDoesNotThrow(() -> service.admit(1L, 10L, "RUN-3"));
        assertEquals(2, service.view().inflight());
    }

    @Test
    void rejectsWhenProjectCapReached() {
        ReflectionTestUtils.setField(service, "maxInflight", 10);
        ReflectionTestUtils.setField(service, "maxPerProject", 1);
        service.admit(1L, null, "RUN-1");
        BusinessException rejected = assertThrows(BusinessException.class,
                () -> service.admit(1L, null, "RUN-2"));
        assertEquals(ErrorCode.CONFLICT, rejected.errorCode());
        assertDoesNotThrow(() -> service.admit(2L, null, "RUN-2"));
    }

    @Test
    void rejectsWhenNodeCapReached() {
        ReflectionTestUtils.setField(service, "maxInflight", 10);
        ReflectionTestUtils.setField(service, "maxPerProject", 10);
        ReflectionTestUtils.setField(service, "maxPerNode", 1);
        service.admit(1L, 7L, "RUN-1");
        assertThrows(BusinessException.class, () -> service.admit(1L, 7L, "RUN-2"));
        assertDoesNotThrow(() -> service.admit(1L, 8L, "RUN-2"));
    }

    @Test
    void admitIsIdempotentForSameRun() {
        service.admit(1L, 3L, "RUN-1");
        assertDoesNotThrow(() -> service.admit(1L, 3L, "RUN-1"));
        assertEquals(1, service.view().inflight());
    }

    @Test
    void blankRunIdIsRejected() {
        BusinessException invalid = assertThrows(BusinessException.class,
                () -> service.admit(1L, null, " "));
        assertEquals(ErrorCode.INVALID_ARGUMENT, invalid.errorCode());
    }
}
