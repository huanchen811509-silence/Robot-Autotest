package com.qa.autotest.application.asset;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.host.HostInventoryService;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.SecretCipher;
import com.qa.autotest.domain.host.HostInventoryRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** FR-v5.13-04：只填空、绝不覆盖非空；失败不填空。 */
class AutoBindTest {

    private JdbcTemplate jdbc;
    private HostInventoryService hostInventory;
    private GlobalAssetService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        hostInventory = mock(HostInventoryService.class);
        service = new GlobalAssetService(jdbc, mock(AccessControlService.class),
                mock(AuditService.class), mock(ProjectAssetRepository.class),
                mock(PlatformProjectService.class), hostInventory,
                mock(SecretCipher.class), new ObjectMapper(),
                mock(CredentialVerificationService.class));
        when(hostInventory.collect(anyLong(), anyString(), any(), any(), any(), any(),
                anyBoolean())).thenReturn(new HostInventoryRecord(1L, 0L, 9L, "h",
                Instant.now(), "OK", "{}", "hash", 1, 0, null));
    }

    private void device(Long mgmtCred) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 9L);
        row.put("name", "gpu-01");
        row.put("management_ip", "10.0.0.1");
        if (mgmtCred != null) row.put("management_credential_id", mgmtCred);
        doReturn(List.of(row)).when(jdbc).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("未绑设备首采成功 → 执行带 IS NULL 的回填")
    void firstCollectFillsNullBinding() {
        device(null);

        service.collect(9L, 3L, null, true);

        verify(jdbc).update(
                argThat(sql -> sql.contains("management_credential_id IS NULL")),
                any(Object[].class));
    }

    @Test
    @DisplayName("核心判据：已绑 A 用 B 采集成功 → 唯一绑定写入 SQL 仍带 IS NULL（DB 命中 0 行）")
    void nonNullBindingIsNeverOverwritten() {
        device(3L);

        service.collect(9L, 7L, null, true);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(jdbc, atLeastOnce()).update(captor.capture(), any(Object[].class));
        List<String> bindSql = captor.getAllValues().stream()
                .filter(sql -> sql.contains("management_credential_id = ?")).toList();
        assertTrue(!bindSql.isEmpty(), "自动填空仍应尝试一次（由 DB 判空）");
        assertTrue(bindSql.stream().allMatch(sql -> sql.contains("IS NULL")),
                "任何不带 IS NULL 的绑定写入都会覆盖人工配置");
    }

    @Test
    @DisplayName("采集失败 → 不执行任何自动填空")
    void failedCollectDoesNotAutoBind() {
        device(null);
        doThrow(new BusinessException(ErrorCode.INVALID_ARGUMENT, "connect timeout"))
                .when(hostInventory).collect(anyLong(), anyString(), any(), any(), any(),
                        any(), anyBoolean());

        assertThrows(BusinessException.class, () -> service.collect(9L, 3L, null, true));

        verify(jdbc, never()).update(
                argThat(sql -> sql.contains("management_credential_id IS NULL")),
                any(Object[].class));
    }

    @Test
    @DisplayName("代码机检：autoBindIfNull 的 SQL 只有一份且恒带 IS NULL")
    void autoBindSqlIsSingleSource() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/qa/autotest/application/asset/GlobalAssetService.java"));
        int occurrences = source.split("management_credential_id IS NULL", -1).length - 1;
        assertTrue(occurrences == 1, "自动填空条件只能有一处，实际 " + occurrences);
        assertTrue(source.contains("autoBindIfNull(deviceId, usedCredentialId)"));
        assertTrue(source.contains("if (ok && \"MGMT\".equals(channel))"));
    }
}
