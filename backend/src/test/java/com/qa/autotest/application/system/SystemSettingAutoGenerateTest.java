package com.qa.autotest.application.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.SystemSettingRepository;
import com.qa.autotest.common.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * v5.14.0 / W64-09 / TC-v5.14-32：设置页开关能真正落库。
 *
 * <p>此前 {@code ReportAutoGenerator} 读得到这个键，但 PUT 会被白名单拒掉；
 * 只测「页面有开关」无法发现这类断链，所以这里直接打服务层写入口。
 */
class SystemSettingAutoGenerateTest {

    private final SystemSettingRepository repository = mock(SystemSettingRepository.class);
    private final AuditService audit = mock(AuditService.class);
    private final SystemSettingService service = new SystemSettingService(repository, audit);

    @Test
    void enabledKeyIsWritable() {
        when(repository.findAllProject(1L))
                .thenReturn(Map.of(SystemSettingService.P_REPORT_AUTO_GENERATE, "true"));
        Map<String, String> result = service.putProject(1L,
                Map.of(SystemSettingService.P_REPORT_AUTO_GENERATE, "true"));
        assertEquals("true", result.get(SystemSettingService.P_REPORT_AUTO_GENERATE));
        verify(repository).upsertProject(eq(1L),
                eq(SystemSettingService.P_REPORT_AUTO_GENERATE), eq("true"), any());
    }

    @Test
    void defaultValueIsOff() {
        when(repository.findAllProject(2L)).thenReturn(Map.of());
        assertEquals("false", service.project(2L).get("report.auto_generate"));
    }

    @Test
    void nonBooleanIsRejected() {
        assertThrows(BusinessException.class, () -> service.putProject(1L,
                Map.of(SystemSettingService.P_REPORT_AUTO_GENERATE, "yes")));
    }
}
