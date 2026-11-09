package com.qa.autotest.api.admin;

import com.qa.autotest.application.caseasset.CapabilityCache;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.async.BoundedExecutor;
import com.qa.autotest.common.cache.TtlCache;
import com.qa.autotest.common.db.SlowQuery;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行看板 v1（EW32-07）：把「缓存命中率 / 异步积压 / 慢查询计数 / 进程运行时长」
 * 汇总成一个只读端点，供前端后续做运维卡片，也方便值班时一条 curl 看全貌。
 *
 * <p>仅管理员可读：这些数字能反映其他租户的负载规模。
 */
@RestController
@RequestMapping("/api/admin/observability")
public class ObservabilityController {

    private final AccessControlService access;
    private final CapabilityCache capabilityCache;
    private final BoundedExecutor auditExecutor;

    public ObservabilityController(AccessControlService access, CapabilityCache capabilityCache,
                                   BoundedExecutor auditExecutor) {
        this.access = access;
        this.capabilityCache = capabilityCache;
        this.auditExecutor = auditExecutor;
    }

    @GetMapping("/summary")
    public ApiResponse<Summary> summary() {
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可查看运行看板");
        }
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
        return ApiResponse.ok(new Summary(
                capabilityCache.stats(),
                auditExecutor.stats(),
                SlowQuery.stats(),
                uptimeSeconds,
                Instant.now()));
    }

    /** 看板 v1 的全部指标。字段即契约，前端按此渲染。 */
    public record Summary(TtlCache.Stats cache, BoundedExecutor.Stats async, SlowQuery.Stats slowQuery,
                          long uptimeSeconds, Instant generatedAt) {
    }
}
