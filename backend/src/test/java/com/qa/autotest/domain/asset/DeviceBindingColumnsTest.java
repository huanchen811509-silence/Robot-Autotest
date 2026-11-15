package com.qa.autotest.domain.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FR-v5.13-10：实体 + 项目侧仓储列清单都补齐两个绑定列，且旧构造器不破。 */
class DeviceBindingColumnsTest {

    @Test
    @DisplayName("Device record 有 22 个分量，含两个绑定 id")
    void recordHasBindingColumns() {
        Set<String> names = Arrays.stream(Device.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toSet());
        assertEquals(22, names.size());
        assertTrue(names.contains("managementCredentialId"));
        assertTrue(names.contains("bmcCredentialId"));
    }

    @Test
    @DisplayName("14 / 19 / 20 参旧构造器仍可用，新字段为 null")
    void legacyConstructorsStayCompatible() {
        Device d14 = new Device(1L, 2L, "n", "SERVER", null, null, null, "10.0.0.1",
                null, null, null, true, null, null);
        assertNull(d14.managementCredentialId());
        assertNull(d14.bmcCredentialId());

        Device d19 = new Device(1L, 2L, "n", "SERVER", null, null, null, "10.0.0.1",
                null, null, null, true, null, null, "host", "linux", "5.15", "x86_64", 22);
        assertNull(d19.managementCredentialId());

        Device d20 = new Device(1L, 2L, "n", "SERVER", null, null, null, "10.0.0.1",
                null, null, null, true, null, null, "host", "linux", "5.15", "x86_64", 22, 7L);
        assertEquals(7L, d20.modelDictId());
        assertNull(d20.bmcCredentialId());
    }

    @Test
    @DisplayName("DeviceRepositoryJdbc 的 SELECT 与 mapRow 都读两列")
    void repositorySelectsBothColumns() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/qa/autotest/infrastructure/DeviceRepositoryJdbc.java"));
        assertTrue(source.contains("d.management_credential_id"));
        assertTrue(source.contains("d.bmc_credential_id"));
        assertTrue(source.contains("rs.getObject(\"management_credential_id\", Long.class)"));
        assertTrue(source.contains("rs.getObject(\"bmc_credential_id\", Long.class)"));
    }
}
