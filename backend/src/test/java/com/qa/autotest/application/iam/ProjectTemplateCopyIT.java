package com.qa.autotest.application.iam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.asset.ProjectAssetService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.Project;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 从模板建项目 → 用例复制链路的**真实库**集成测试（v4.10.0 / L-49-08）。
 *
 * <p><b>为什么非要有这一条</b>：{@code ProjectTemplateServiceTest} 用 mock JdbcTemplate 验证
 * 编排顺序与事务边界，SQL 文本从未真正执行。而 {@code copyCase} 里手写的列清单引用了
 * 早已重命名掉的 {@code category} 列——四个内置模板内容全为空（L-49-02），这段代码
 * 自 v4.9.2 起**一次都没被执行过**，于是「能建项目」的假绿一直存在，直到补齐模板内容
 * 后立刻 500 {@code Unknown column 'category' in 'field list'}。
 *
 * <p>本测试直接连 robot_it：种一条带 A端/B端/交换机/测试结果/测试资源五列的用例，
 * 挂到模板上，走完整 {@code createProjectFromTemplate}，再断言副本把这五列与两级分类
 * 一并搬了过去。SQL 列名只要再漂一次，这里必然红。
 *
 * <p>库名守卫：{@code DB_NAME} 必须以 {@code _it} 结尾，防止误清开发库。
 */
@Tag("it")
class ProjectTemplateCopyIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");
    private static final long OPERATOR = 1L;

    private JdbcTemplate jdbc;
    private ProjectTemplateService service;
    private long sourceProjectId;
    private long templateId;
    private long sourceCaseId;
    private long createdProjectId;

    @BeforeEach
    void setUp() {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误清开发库").endsWith("_it");
        jdbc = new JdbcTemplate(dataSource());
        createdProjectId = 0L;

        AccessControlService access = mock(AccessControlService.class);
        AuditService audit = mock(AuditService.class);
        ProjectAssetService projectAssets = mock(ProjectAssetService.class);
        ProjectAssetRepository assetRepository = mock(ProjectAssetRepository.class);
        ProjectService projectService = mock(ProjectService.class);
        // 建项目交给真实 INSERT，只把「编码生成/成员初始化/审计」用桩替掉
        when(projectService.create(anyString(), anyString(), any(), anyString(), anyString()))
                .thenAnswer(inv -> {
                    String code = inv.getArgument(0);
                    String name = inv.getArgument(1);
                    String description = inv.getArgument(2);
                    String visibility = inv.getArgument(3);
                    String kernel = inv.getArgument(4);
                    jdbc.update("""
                            INSERT INTO project (code, name, description, visibility, test_kernel,
                                                 kind, status, created_by, updated_by)
                            VALUES (?, ?, ?, ?, ?, 'standard', 1, ?, ?)
                            """, code, name, description, visibility, kernel, OPERATOR, OPERATOR);
                    createdProjectId = jdbc.queryForObject(
                            "SELECT id FROM project WHERE code = ?", Long.class, code);
                    return new Project(createdProjectId, code, name, description, true, OPERATOR,
                            visibility, kernel, "standard", null);
                });

        service = new ProjectTemplateService(jdbc, access, audit, projectAssets,
                assetRepository, projectService);

        TenantContext.set(OPERATOR, "it-template", null);
        String stamp = String.valueOf(System.nanoTime());

        jdbc.update("""
                INSERT INTO project (code, name, description, visibility, test_kernel, kind,
                                     status, created_by, updated_by)
                VALUES (?, 'IT 源项目', 'it', 'private', 'robot', 'standard', 1, ?, ?)
                """, "IT-SRC-" + stamp, OPERATOR, OPERATOR);
        sourceProjectId = jdbc.queryForObject(
                "SELECT id FROM project WHERE code = ?", Long.class, "IT-SRC-" + stamp);

        jdbc.update("""
                INSERT INTO test_case (project_id, case_code, external_id, title, case_kind,
                    source_type, priority, lifecycle_status, version_no, status, safety_level,
                    a_side_command, b_side_command, switch_config, test_result, test_resource,
                    category_l1, category_l2, created_by, updated_by)
                VALUES (?, ?, 'IT-EXT-1', 'IT 复制用例', '功能', 'ledger', 'P2', 'active', 1, 1, 'LX',
                    'ssh node "ethtool -i eth0"', 'ssh peer "ping -c1 10.0.0.1"',
                    'interface Ethernet1/1', 'PASS', 'gpu-node-01 + 800G 交换机', '以太网功能测试',
                    '链路', ?, ?)
                """, sourceProjectId, "IT-CASE-" + stamp, OPERATOR, OPERATOR);
        sourceCaseId = jdbc.queryForObject(
                "SELECT id FROM test_case WHERE case_code = ?", Long.class, "IT-CASE-" + stamp);

        jdbc.update("""
                INSERT INTO project_template (name, code, kernel, visibility, builtin, status,
                                              created_by, updated_by)
                VALUES ('IT 模板', ?, 'robot', 'team', 0, 'active', ?, ?)
                """, "IT-TPL-" + stamp, OPERATOR, OPERATOR);
        templateId = jdbc.queryForObject(
                "SELECT id FROM project_template WHERE code = ?", Long.class, "IT-TPL-" + stamp);
        jdbc.update("""
                INSERT INTO project_template_item
                    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_by, updated_by)
                VALUES (?, 'CASE', ?, 'copy', 1, 'IT 复制用例', ?, ?)
                """, templateId, sourceCaseId, OPERATOR, OPERATOR);
    }

    @AfterEach
    void tearDown() {
        if (createdProjectId > 0) {
            jdbc.update("DELETE FROM test_set_item WHERE test_set_id IN "
                    + "(SELECT id FROM test_set WHERE project_id = ?)", createdProjectId);
            jdbc.update("DELETE FROM test_set WHERE project_id = ?", createdProjectId);
            jdbc.update("DELETE FROM test_scenario WHERE project_id = ?", createdProjectId);
            jdbc.update("DELETE FROM project_asset WHERE project_id = ?", createdProjectId);
            jdbc.update("DELETE FROM test_case WHERE project_id = ?", createdProjectId);
            jdbc.update("DELETE FROM project WHERE id = ?", createdProjectId);
        }
        jdbc.update("DELETE FROM test_case_step WHERE test_case_id = ?", sourceCaseId);
        jdbc.update("DELETE FROM test_case WHERE id = ?", sourceCaseId);
        jdbc.update("DELETE FROM project WHERE id = ?", sourceProjectId);
        jdbc.update("DELETE FROM project_template_item WHERE template_id = ?", templateId);
        jdbc.update("DELETE FROM project_template_asset WHERE template_id = ?", templateId);
        jdbc.update("DELETE FROM project_template WHERE id = ?", templateId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("从模板建项目：用例副本带上 A端/B端/交换机/测试结果/测试资源五列与两级分类")
    void copiesCaseWithExtraColumns() {
        // 显式 code：绕开 ProjectService.nextCode（桩未实现），让断言聚焦在复制链路本身
        String code = "IT-PRJ-" + System.nanoTime();
        Project created = service.createProjectFromTemplate(new CreateProjectFromTemplateRequest(
                templateId, "IT 新项目-" + System.nanoTime(), code, "copy"));

        assertThat(created.id()).isEqualTo(createdProjectId);

        List<Map<String, Object>> copied = jdbc.queryForList(
                "SELECT * FROM test_case WHERE project_id = ?", createdProjectId);
        assertThat(copied).as("模板里的 CASE 项应被复制成项目用例").hasSize(1);
        Map<String, Object> row = copied.get(0);
        assertThat(row.get("a_side_command")).isEqualTo("ssh node \"ethtool -i eth0\"");
        assertThat(row.get("b_side_command")).isEqualTo("ssh peer \"ping -c1 10.0.0.1\"");
        assertThat(row.get("switch_config")).isEqualTo("interface Ethernet1/1");
        assertThat(row.get("test_result")).isEqualTo("PASS");
        assertThat(row.get("test_resource")).isEqualTo("gpu-node-01 + 800G 交换机");
        assertThat(row.get("category_l1")).isEqualTo("以太网功能测试");
        assertThat(row.get("category_l2")).isEqualTo("链路");
        assertThat(row.get("lifecycle_status")).isEqualTo("active");
        assertThat(row.get("version_no")).isEqualTo(1);
        // copy 模式必须与公共库解耦：指纹不得与原用例相同
        assertThat(row.get("content_sha")).isNotEqualTo(
                jdbc.queryForObject("SELECT content_sha FROM test_case WHERE id = ?",
                        String.class, sourceCaseId));

        Long setItems = jdbc.queryForObject("""
                SELECT COUNT(*) FROM test_set_item i JOIN test_set s ON s.id = i.test_set_id
                 WHERE s.project_id = ?
                """, Long.class, createdProjectId);
        assertThat(setItems).as("复制出的用例应进入「模板导入用例集」").isEqualTo(1L);
    }

    @Test
    @DisplayName("模板含 SCENARIO 项：场景的 case_set_ids 指向本轮真实产出的用例集（L-49-07）")
    void scenarioReferencesCreatedCaseSets() {
        // 模板里加一个场景项（排在 CASE 之后），建项目后它的 case_set_ids 不应为空。
        jdbc.update("""
                INSERT INTO project_template_item
                    (template_id, item_type, ref_id, mode, sort_no, snapshot_json, created_by, updated_by)
                VALUES (?, 'SCENARIO', NULL, 'copy', 2, 'IT 场景', ?, ?)
                """, templateId, OPERATOR, OPERATOR);

        String code = "IT-SCN-" + System.nanoTime();
        service.createProjectFromTemplate(new CreateProjectFromTemplateRequest(
                templateId, "IT 场景项目-" + System.nanoTime(), code, "copy"));

        List<String> setIds = jdbc.queryForList(
                "SELECT case_set_ids FROM test_scenario WHERE project_id = ?",
                String.class, createdProjectId);
        assertThat(setIds).as("场景壳必须建出来").hasSize(1);
        assertThat(setIds.get(0))
                .as("case_set_ids 不能是空串——否则场景详情永远没有用例集（L-49-07）")
                .isNotBlank();

        // 写入的 id 必须是本项目真实存在的用例集，而不是别的项目 / 不存在的号
        List<Long> realSetIds = jdbc.queryForList(
                "SELECT id FROM test_set WHERE project_id = ?", Long.class, createdProjectId);
        assertThat(realSetIds).isNotEmpty();
        for (String part : setIds.get(0).split(",")) {
            assertThat(realSetIds).contains(Long.valueOf(part.trim()));
        }
    }

    private static DriverManagerDataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        ds.setDriverClassName("org.mariadb.jdbc.Driver");
        ds.setUrl("jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME);
        ds.setUsername(System.getenv().getOrDefault("DB_USERNAME", "robot"));
        ds.setPassword(System.getenv().getOrDefault("DB_PASSWORD", ""));
        return ds;
    }
}
