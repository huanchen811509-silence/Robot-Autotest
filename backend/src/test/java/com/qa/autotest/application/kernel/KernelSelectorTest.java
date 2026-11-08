package com.qa.autotest.application.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.qa.autotest.application.repository.SystemSettingRepository;
import com.qa.autotest.application.system.SystemSettingService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.domain.kernel.KernelSelection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KernelSelectorTest {

    private static SystemSettingRepository settings(String value) {
        return new SystemSettingRepository() {
            @Override public Map<String, String> findAllSystem() { return Map.of(); }
            @Override public Optional<String> findSystem(String key) { return Optional.empty(); }
            @Override public void upsertSystem(String key, String v, Long actor) { }
            @Override public Map<String, String> findAllProject(long projectId) { return Map.of(); }
            @Override public Optional<String> findProject(long projectId, String key) {
                return SystemSettingService.P_DEFAULT_KERNEL.equals(key)
                        ? Optional.ofNullable(value) : Optional.empty();
            }
            @Override public void upsertProject(long projectId, String key, String v, Long actor) { }
        };
    }

    private static KernelRegistry registry() {
        return new KernelRegistry(List.of(
                new RobotKernel(new com.qa.autotest.application.execution.ExecutionProperties(),
                        new OutputXmlNormalizer()),
                new PytestKernel(new com.fasterxml.jackson.databind.ObjectMapper(),
                        new com.qa.autotest.application.execution.ExecutionProperties())));
    }

    @Test
    void projectDefaultWinsWhenNoOverride() {
        KernelSelection s = new KernelSelector(registry(), settings("robot"))
                .select(1L, null, null);
        assertEquals("robot", s.kernel());
        assertEquals("PROJECT_DEFAULT", s.source());
        assertFalse(s.overridden());
    }

    @Test
    void caseOverrideBeatsSetOverrideAndMarksSource() {
        KernelSelection s = new KernelSelector(registry(), settings("robot"))
                .select(1L, "pytest", "ROBOT");
        assertEquals("robot", s.kernel());
        assertEquals("CASE_OVERRIDE", s.source());
        assertTrue(s.overridden());
    }

    @Test
    void unknownKernelIsRejectedNotSilentlyFallenBack() {
        assertThrows(BusinessException.class,
                () -> new KernelSelector(registry(), settings("robot"))
                        .select(1L, null, "nonexistent"));
    }

    @Test
    void registryRejectsDuplicateNames() {
        assertThrows(IllegalStateException.class,
                () -> new KernelRegistry(List.of(
                        new PytestKernel(new com.fasterxml.jackson.databind.ObjectMapper(),
                        new com.qa.autotest.application.execution.ExecutionProperties()),
                        new PytestKernel(new com.fasterxml.jackson.databind.ObjectMapper(),
                        new com.qa.autotest.application.execution.ExecutionProperties()))));
    }
}
