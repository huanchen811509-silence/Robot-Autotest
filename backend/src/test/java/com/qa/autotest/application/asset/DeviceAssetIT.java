package com.qa.autotest.application.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.asset.Device;
import com.qa.autotest.domain.asset.DevicePort;
import com.qa.autotest.infrastructure.AuditRepositoryJdbc;
import com.qa.autotest.infrastructure.DeviceRepositoryJdbc;
import com.qa.autotest.infrastructure.ProjectAssetRepositoryJdbc;
import com.qa.autotest.infrastructure.ProjectRepositoryJdbc;
import com.qa.autotest.infrastructure.UserRepositoryJdbc;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * AST-01 的「真实库」集成测试：设备/端口台账、唯一性、平台共享资产的删除闸门、跨项目越权。
 *
 * <p><b>为什么要有这一条</b>：{@code DeviceServiceTest} 全部 mock 仓储，
 * 只能证明「服务层照着 if 走」，证明不了 SQL 真的按 {@link com.qa.autotest.infrastructure.AssetScopeSql}
 * 的读口径（私有 OR 平台共享且已引用）筛数据，也证明不了 {@code serialExists} /
 * {@code portNameExists} 的 {@code id <> ?} 排除自身在真表上成立。
 *
 * <p><b>本类覆盖</b>：
 * <ul>
 *   <li>台账往返：创建 → 回读字段 → 关键字/IP/领域筛选 → 领域去重排序。</li>
 *   <li>唯一性：同项目内序列号重复（创建与更新两条路径）落 409；不同项目同序列号互不影响。</li>
 *   <li>端口：增删改查 + 同名端口 409 + 改自身同名不算冲突（{@code id <> ?} 的边界）。</li>
 *   <li>平台共享：共享后删除必须 409（FR-v0.2-51），且行仍在。</li>
 *   <li>越权：非项目成员一律 403，消息不泄露项目是否存在。</li>
 * </ul>
 *
 * <p>连库方式与 {@code CaseImportIT} 一致（ADR-0002：不用 H2），库名必须以 {@code _it} 结尾。
 */
@Tag("it")
class DeviceAssetIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");

    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private DeviceService service;
    private ProjectAssetRepository projectAssets;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> projectIds = new ArrayList<>();
    private final List<Long> deviceIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误清开发库").endsWith("_it");
        context = new AnnotationConfigApplicationContext(ItConfig.class);
        jdbc = context.getBean(JdbcTemplate.class);
        service = context.getBean(DeviceService.class);
        projectAssets = context.getBean(ProjectAssetRepository.class);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        try {
            for (Long deviceId : deviceIds) {
                jdbc.update("DELETE FROM device_port WHERE device_id = ?", deviceId);
                jdbc.update("DELETE FROM project_asset WHERE asset_type = 'DEVICE' AND asset_id = ?", deviceId);
                jdbc.update("DELETE FROM device WHERE id = ?", deviceId);
            }
            for (Long projectId : projectIds) {
                jdbc.update("DELETE FROM project_asset WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM audit_log WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project_member WHERE project_id = ?", projectId);
                jdbc.update("DELETE FROM project WHERE id = ?", projectId);
            }
            for (Long userId : userIds) {
                jdbc.update("DELETE FROM user_role WHERE user_id = ?", userId);
                jdbc.update("DELETE FROM app_user WHERE id = ?", userId);
            }
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("台账往返：创建后字段原样回读，关键字/IP/领域/状态四个筛选项都命中")
    void 台账往返与筛选() {
        long[] seed = seed("admin");
        TenantContext.set(seed[0], "it-ast01-" + seed[0], null);
        long projectId = seed[1];

        Device gpu = service.create(projectId,
                cmd("GPU-01", "SN-GPU-01", "10.20.0.11", "GPU", true));
        Device sw = service.create(projectId,
                cmd("SW-01", "SN-SW-01", "10.20.0.21", "RDMA", true));
        deviceIds.add(gpu.id());
        deviceIds.add(sw.id());

        Device reread = service.get(projectId, gpu.id());
        assertThat(reread.name()).isEqualTo("GPU-01");
        assertThat(reread.serialNo()).isEqualTo("SN-GPU-01");
        assertThat(reread.managementIp()).isEqualTo("10.20.0.11");
        assertThat(reread.domainTag()).isEqualTo("GPU");

        assertThat(service.page(projectId, "10.20.0.11", null, null, 1, 20).total())
                .as("关键字同时匹配名称/IP/序列号，按 IP 应只命中 GPU-01").isEqualTo(1);
        assertThat(service.page(projectId, "SN-SW-01", null, null, 1, 20).items())
                .extracting(Device::name).containsExactly("SW-01");
        assertThat(service.page(projectId, null, null, "RDMA", 1, 20).total()).isEqualTo(1);
        assertThat(service.page(projectId, null, 0, null, 1, 20).total())
                .as("两台都是启用状态，status=0 应为 0 条").isZero();
        assertThat(service.domains(projectId))
                .as("领域去重且按字典序").containsExactly("GPU", "RDMA");
    }

    @Test
    @DisplayName("序列号唯一：同项目重复落 409（创建/更新两条路径），跨项目互不影响")
    void 序列号唯一() {
        long[] a = seed("admin");
        TenantContext.set(a[0], "it-ast01-a-" + a[0], null);
        Device first = service.create(a[1], cmd("GPU-01", "SN-DUP", "10.20.1.1", "GPU", true));
        deviceIds.add(first.id());

        TenantContext.set(a[0], "it-ast01-a-" + a[0], null);
        assertThatThrownBy(() -> service.create(a[1], cmd("GPU-02", "SN-DUP", "10.20.1.2", "GPU", true)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.getMessage()).contains("序列号已存在");
                });

        Device second = service.create(a[1], cmd("GPU-02", "SN-OTHER", "10.20.1.2", "GPU", true));
        deviceIds.add(second.id());
        assertThatThrownBy(() -> service.update(a[1], second.id(),
                cmd("GPU-02", "SN-DUP", "10.20.1.2", "GPU", true)))
                .as("更新成别人的序列号同样 409")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.CONFLICT));

        // 改自己的序列号（值不变）不能因为 id 排除写错而误报
        Device same = service.update(a[1], second.id(),
                cmd("GPU-02", "SN-OTHER", "10.20.1.2", "GPU", true));
        assertThat(same.serialNo()).isEqualTo("SN-OTHER");

        long[] b = seed("admin");
        TenantContext.set(b[0], "it-ast01-b-" + b[0], null);
        Device other = service.create(b[1], cmd("GPU-01", "SN-DUP", "10.20.2.1", "GPU", true));
        deviceIds.add(other.id());
        assertThat(other.serialNo())
                .as("序列号是项目内唯一，不是全局唯一").isEqualTo("SN-DUP");
    }

    @Test
    @DisplayName("端口：增删改查 + 同名 409 + 改自身同名不冲突")
    void 端口增删改查() {
        long[] seed = seed("admin");
        TenantContext.set(seed[0], "it-ast01-p-" + seed[0], null);
        long projectId = seed[1];
        Device device = service.create(projectId, cmd("GPU-03", "SN-PORT", "10.20.3.1", "GPU", true));
        deviceIds.add(device.id());

        DevicePort port = service.addPort(projectId, device.id(),
                new DeviceService.PortCommand("mlx5_0", "QSFP", "RoCE", "00:11:22:33:44:55",
                        200, 4200, "10.20.3.11", "UP", "mlx5_0", 0));
        assertThat(port.portName()).isEqualTo("mlx5_0");
        assertThat(port.speedGbps()).isEqualTo(200);
        // v4.3.0：网卡归属随端口落库并可回读
        assertThat(port.nicName()).isEqualTo("mlx5_0");
        assertThat(port.nicIndex()).isZero();
        assertThat(service.ports(projectId, device.id())).hasSize(1);

        assertThatThrownBy(() -> service.addPort(projectId, device.id(),
                new DeviceService.PortCommand("mlx5_0", "QSFP", "RoCE", null, 100, null, null,
                        null, "mlx5_0", 1)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.getMessage()).contains("端口名已存在");
                });

        DevicePort updated = service.updatePort(projectId, port.id(),
                new DeviceService.PortCommand("mlx5_0", "QSFP", "RoCE", "00:11:22:33:44:55",
                        100, 4200, "10.20.3.11", "DOWN", "mlx5_1", 2));
        assertThat(updated.speedGbps()).isEqualTo(100);
        assertThat(updated.portStatus()).isEqualTo("DOWN");
        assertThat(updated.nicName()).isEqualTo("mlx5_1");
        assertThat(updated.nicIndex()).isEqualTo(2);

        service.deletePort(projectId, port.id());
        assertThat(service.ports(projectId, device.id())).isEmpty();
    }

    @Test
    @DisplayName("平台共享资产：被引用时硬删必须 409，行不能被删掉")
    void 平台共享资产不可硬删() {
        long[] seed = seed("admin");
        TenantContext.set(seed[0], "it-ast01-s-" + seed[0], null);
        long projectId = seed[1];
        Device device = service.create(projectId, cmd("SHARED-01", "SN-SHARE", "10.20.4.1", "GPU", true));
        deviceIds.add(device.id());

        projectAssets.shareToPlatform("DEVICE", device.id(), projectId);
        projectAssets.addReference(projectId, "DEVICE", device.id(), "共享给我", seed[0]);

        assertThat(service.get(projectId, device.id()).name())
                .as("共享后靠 project_asset 引用行仍然可见").isEqualTo("SHARED-01");
        assertThatThrownBy(() -> service.delete(projectId, device.id()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.CONFLICT);
                    assertThat(ex.getMessage()).contains("平台共享资产");
                });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device WHERE id = ?", Long.class, device.id()))
                .as("409 之后设备行必须还在").isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT project_id FROM device WHERE id = ?", Long.class, device.id()))
                .as("共享后 project_id 置空（平台资产）").isNull();
    }

    @Test
    @DisplayName("越权：非项目成员读设备 403，消息不泄露项目是否存在")
    void 非成员403() {
        long[] owner = seed("admin");
        long projectId = owner[1];
        TenantContext.set(owner[0], "it-ast01-o-" + owner[0], null);
        Device device = service.create(projectId, cmd("GPU-04", "SN-ACL", "10.20.5.1", "GPU", true));
        deviceIds.add(device.id());

        long[] outsider = seed(null);
        TenantContext.set(outsider[0], "it-ast01-x-" + outsider[0], null);
        assertThatThrownBy(() -> service.page(projectId, null, null, null, 1, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
                    assertThat(ex.getMessage()).isEqualTo("无权访问该项目");
                });
        assertThatThrownBy(() -> service.get(projectId, device.id()))
                .as("非成员连单条都不能读").isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).errorCode())
                        .isEqualTo(ErrorCode.FORBIDDEN));
    }

    // ------------------------------------------------------------------
    // 最小 Spring 上下文：只装配被测链路需要的 Bean，不起 Boot、不跑 Flyway
    // ------------------------------------------------------------------

    @Configuration
    static class ItConfig {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(dataSourceUrl(), username(), password());
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        DeviceRepository deviceRepository(JdbcTemplate jdbc) {
            return new DeviceRepositoryJdbc(jdbc);
        }

        @Bean
        ProjectAssetRepository projectAssetRepository(JdbcTemplate jdbc) {
            return new ProjectAssetRepositoryJdbc(jdbc);
        }

        @Bean
        AccessControlService accessControlService(JdbcTemplate jdbc) {
            return new AccessControlService(new UserRepositoryJdbc(jdbc), new ProjectRepositoryJdbc(jdbc));
        }

        @Bean
        AuditService auditService(JdbcTemplate jdbc) {
            return new AuditService(new AuditRepositoryJdbc(jdbc));
        }

        @Bean
        DeviceService deviceService(DeviceRepository devices, ProjectAssetRepository projectAssets,
                                    AccessControlService access, AuditService audit) {
            return new DeviceService(devices, projectAssets, access, audit);
        }
    }

    private static String dataSourceUrl() {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        // 驱动是 MariaDB Java client，前缀必须是 jdbc:mariadb://（写 jdbc:mysql:// 会报
        // No suitable driver found，看着像「驱动没引」，实际是前缀不对）。
        return "jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME
                + "?useUnicode=true&characterEncoding=utf8";
    }

    private static String username() {
        return System.getenv().getOrDefault("DB_USERNAME", "robot");
    }

    private static String password() {
        return System.getenv().getOrDefault("DB_PASSWORD", "");
    }

    // ------------------------------------------------------------------
    // 造数据 / 清理
    // ------------------------------------------------------------------

    /** 建用户 + 项目 + 成员关系；memberRole 为 null 表示「不是项目成员」。返回 [userId, projectId]。 */
    private long[] seed(String memberRole) {
        String stamp = UUID.randomUUID().toString().substring(0, 8);
        String username = "it-ast01-" + stamp;
        String projectCode = "IT-AST01-" + stamp;
        jdbc.update("INSERT INTO app_user (username, password_hash, display_name, status) "
                + "VALUES (?, ?, ?, 1)", username, "$2a$10$it-not-a-real-hash", "AST-01 集成测试");
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = ?", Long.class, username);
        jdbc.update("INSERT INTO project (code, name, owner_id, status) VALUES (?, ?, ?, 1)",
                projectCode, "AST-01 集成测试", userId);
        Long projectId = jdbc.queryForObject("SELECT id FROM project WHERE code = ?", Long.class, projectCode);
        if (memberRole != null) {
            jdbc.update("INSERT INTO project_member (project_id, user_id, member_role) VALUES (?, ?, ?)",
                    projectId, userId, memberRole);
        }
        userIds.add(userId);
        projectIds.add(projectId);
        return new long[]{userId, projectId};
    }

    private static DeviceService.DeviceCommand cmd(String name, String serial, String ip,
                                                   String domain, boolean enabled) {
        return new DeviceService.DeviceCommand(name, "SERVER", "R750", "Dell", serial, ip,
                "A01", "R-01", domain, enabled, null);
    }
}
