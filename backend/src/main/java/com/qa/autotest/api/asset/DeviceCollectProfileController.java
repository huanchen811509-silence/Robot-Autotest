package com.qa.autotest.api.asset;

import com.qa.autotest.application.asset.DeviceCollectProfile;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.application.repository.DeviceCollectProfileRepository;
import com.qa.autotest.common.ApiResponse;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采集画像只读端点（v5.13.0 / FR-v5.13-01、FR-v5.13-10）。
 *
 * <p>全局资产页没有项目上下文，可显式传平台项目 id；其余情况一律以当前租户为准。
 * 显式传入的项目 id 必须与当前租户一致（或就是平台项目），否则 403 —— 否则
 * 「换一个 projectId 就能读到别人项目设备」会成为新的越权面（NFR-v5.13-06）。
 */
@RestController
@RequestMapping("/api/assets")
public class DeviceCollectProfileController {

    /** 采集记录都挂在平台项目（collect 传的是 platformId），历史查询因此可以只按 deviceId 问。 */
    /**
     * 采集历史（v5.17.0 / W67-08 加凭证两列）。
     *
     * <p>凭证按「本次采集时刻之前最近一次验证」取：`verified_at <= h.collected_at`。
     * 只按 device 取最近一条会出错——同一台设备换过凭证后，历史行会全部指向新凭证，
     * 那就等于没有历史。名字在应用层掩码（{@link #maskName}），SQL 里不回明文名。
     */
    private static final String SQL_HISTORY = """
            SELECT h.id, h.device_id, h.status,
                   DATE_FORMAT(h.collected_at, '%Y-%m-%d %H:%i:%s') AS collected_at,
                   h.nic_count, h.roce_ports, h.roce_max_gbps,
                   (SELECT cv.credential_id FROM credential_verification cv
                     WHERE cv.device_id = h.device_id AND cv.verified_at <= h.collected_at
                     ORDER BY cv.verified_at DESC, cv.id DESC LIMIT 1) AS credential_id,
                   (SELECT c.name FROM credential_verification cv
                      JOIN credential c ON c.id = cv.credential_id
                     WHERE cv.device_id = h.device_id AND cv.verified_at <= h.collected_at
                     ORDER BY cv.verified_at DESC, cv.id DESC LIMIT 1) AS credential_name
              FROM device_host_spec h
             WHERE h.device_id = ? AND h.project_id = ?
             ORDER BY h.collected_at DESC, h.id DESC LIMIT ?
            """;

    public record CollectHistoryRow(long id, Long deviceId, String status, String collectedAt,
                                    int nicCount, int rocePorts, Integer roceMaxGbps,
                                    Long credentialId, String credentialMaskedName) {
    }

    /**
     * 凭证名掩码（v5.17.0 / W67-08）：只给「看得出是哪一类」的辨识度，不给全名。
     *
     * <p>为什么不回全名：「gpu-rack12-admin-2026」这种名字本身就带位置与用途，
     * 拿到历史列表就等于拿到了环境拓扑的一部分。掩码规则与其它敏感字段一致：
     * 太短就全遮，够长留首尾。
     */
    static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String text = name.trim();
        if (text.length() <= 2) {
            return "***";
        }
        if (text.length() <= 5) {
            return text.charAt(0) + "***" + text.charAt(text.length() - 1);
        }
        return text.substring(0, 3) + "***" + text.substring(text.length() - 2);
    }

    private final DeviceCollectProfileRepository profiles;
    private final AccessControlService access;
    private final PlatformProjectService platform;
    private final JdbcTemplate jdbc;

    public DeviceCollectProfileController(DeviceCollectProfileRepository profiles,
                                          AccessControlService access,
                                          PlatformProjectService platform,
                                          JdbcTemplate jdbc) {
        this.profiles = profiles;
        this.access = access;
        this.platform = platform;
        this.jdbc = jdbc;
    }

    @GetMapping("/collect-profiles")
    public ApiResponse<List<DeviceCollectProfile>> profiles(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) String state) {
        Long current = TenantContext.projectId();
        long scope;
        if (projectId == null) {
            scope = current == null ? platform.id() : current;
        } else {
            scope = projectId;
            if (!projectId.equals(current) && projectId != platform.id()) {
                throw new BusinessException(ErrorCode.FORBIDDEN,
                        "不能查询其他项目的采集画像：" + projectId);
            }
        }
        access.bindProject(scope);
        return ApiResponse.ok(profiles.list(scope, platform.id(), deviceId, state));
    }

    /**
     * 采集历史摘要（v5.13.0 / W63-10）：只回提列数值，不回 spec_json 原文
     * （那是命令回显，对外可见面不能带）。失败记录也在这里，status 由列表展示。
     */
    @GetMapping("/collect-history")
    public ApiResponse<List<CollectHistoryRow>> history(
            @RequestParam long deviceId,
            @RequestParam(defaultValue = "20") int limit) {
        access.requireGlobalEnter();
        TenantGuard.requireProjectScope(SQL_HISTORY, "device_host_spec");
        int capped = Math.max(1, Math.min(limit, 50));
        List<CollectHistoryRow> rows = jdbc.query(SQL_HISTORY, (rs, n) -> new CollectHistoryRow(
                rs.getLong("id"),
                rs.getObject("device_id", Long.class),
                rs.getString("status"),
                rs.getString("collected_at"),
                rs.getInt("nic_count"),
                rs.getInt("roce_ports"),
                rs.getObject("roce_max_gbps", Integer.class),
                rs.getObject("credential_id", Long.class),
                maskName(rs.getString("credential_name"))),
                deviceId, platform.id(), capped);
        return ApiResponse.ok(rows);
    }
}
