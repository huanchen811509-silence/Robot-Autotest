package com.qa.autotest.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 数据库基线集成测试（TC-v0.1-020 ~ 028），必须连真实 MariaDB（ADR-0002：不用 H2）。
 *
 * <p><b>默认被 surefire 跳过</b>（{@code @Tag("it")}）；CI 与本地显式打开（`-Pit` 才会
 * 清空 {@code excludedGroups}，只写 {@code -Dgroups=it} 会与默认的 {@code excludedGroups=it}
 * 抵消成 0 个用例，看着 BUILD SUCCESS 其实一条没跑）：
 * <pre>DB_HOST=127.0.0.1 DB_NAME=robot_it DB_USERNAME=robot DB_PASSWORD=*** \
 *   mvn -Pit test -Dtest=SchemaIT</pre>
 *
 * <p>库名必须带 {@code _it} 后缀，禁止指向开发库或生产库（会 clean 掉整个 schema）。
 */
@Tag("it")
class SchemaIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");

    /**
     * FR-v4.2-02：期望表数与 append-only 清单的唯一真源是 {@code db/schema-contract.json}，
     * 与 {@code scripts/db-audit.sh} 读同一份文件。之前两处各写一套常量，V8 之后已经漂移
     * （SchemaIT 少算了 ai_message / alert_event / slo_daily / release_record / archived_run /
     * device_host_spec，只是 @Tag("it") 默认跳过才没被门禁发现）。
     * {@code SCHEMA_CONTRACT_PATH} 可覆盖路径，供负向自证使用。
     */
    private static final JsonNode CONTRACT = loadContract();

    private static final int EXPECTED_TABLES = CONTRACT.path("expectedTables").asInt();

    /**
     * append-only 表：按设计只增不改，没有「更新人/更新时间」这回事。
     * 要求它们也有 updated_at/updated_by，只会逼业务代码写假值。
     */
    private static final java.util.Set<String> APPEND_ONLY = loadAppendOnly();

    /** append-only 表里必须有 created_at 的那些（其余靠业务时间锚点）。 */
    private static final Set<String> CREATED_AT_REQUIRED = loadNames("createdAtRequired");

    /** 没有 created_at 的 append-only 表，用哪个业务时间列证明「何时发生」。 */
    private static final java.util.Map<String, String> TIME_ANCHORS = loadAnchors();

    private static JsonNode loadContract() {
        Path path = Path.of(System.getenv().getOrDefault("SCHEMA_CONTRACT_PATH",
                "../db/schema-contract.json"));
        try {
            return new ObjectMapper().readTree(Files.readString(path));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 schema 契约: " + path.toAbsolutePath(), e);
        }
    }

    private static Set<String> loadAppendOnly() {
        return loadNames("appendOnlyTables");
    }

    private static Set<String> loadNames(String field) {
        Set<String> names = new LinkedHashSet<>();
        CONTRACT.path(field).forEach(node -> names.add(node.asText()));
        if (names.isEmpty()) {
            throw new IllegalStateException("schema 契约的 " + field + " 不能为空");
        }
        return Set.copyOf(names);
    }

    private static java.util.Map<String, String> loadAnchors() {
        java.util.Map<String, String> anchors = new java.util.LinkedHashMap<>();
        CONTRACT.path("appendOnlyTimeColumns").fields().forEachRemaining(entry -> {
            String column = entry.getValue().asText();
            if (!column.isBlank()) {
                anchors.put(entry.getKey(), column);
            }
        });
        Set<String> uncovered = new LinkedHashSet<>(APPEND_ONLY);
        uncovered.removeAll(CREATED_AT_REQUIRED);
        uncovered.removeAll(anchors.keySet());
        if (!uncovered.isEmpty()) {
            throw new IllegalStateException("append-only 表缺少 created_at / 时间锚点: " + uncovered);
        }
        return java.util.Map.copyOf(anchors);
    }

    private static Flyway flyway;

    @BeforeAll
    static void migrateFromScratch() {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误清开发库").endsWith("_it");
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String user = System.getenv().getOrDefault("DB_USERNAME", "robot");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        String locations = System.getenv().getOrDefault("DB_MIGRATION_PATH", "filesystem:../db/migration");

        flyway = Flyway.configure()
                .dataSource("jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME, user, password)
                .locations(locations)
                .cleanDisabled(false)
                // 关掉占位符替换：V2_5 的注释里写了「面板据此补 ${} 占位符」这句说明，
                // Flyway 10 会把它当空名占位符并直接失败（本文件与 pom 插件都要关）。
                .placeholderReplacement(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    @DisplayName("TC-v0.1-020 空库迁移后业务表数与基线一致")
    void 迁移后表数() throws Exception {
        assertThat(表名清单()).hasSize(EXPECTED_TABLES);
    }

    @Test
    @DisplayName("TC-v0.1-023 每张表都有审计四列（append-only 表只要求「何时发生」可追溯）")
    void 审计四列齐全() throws Exception {
        List<String> 缺列的表 = new ArrayList<>();
        for (String table : 表名清单()) {
            List<String> columns = 列名(table);
            if (APPEND_ONLY.contains(table)) {
                // append-only 表不写 updated_*，但必须能回答「这条是什么时候发生的」：
                // 契约声明的 created_at 必须有；没声明 created_at 的，业务时间锚点必须有。
                if (CREATED_AT_REQUIRED.contains(table) && !columns.contains("created_at")) {
                    缺列的表.add(table + "（append-only，缺 created_at）");
                }
                String anchor = TIME_ANCHORS.get(table);
                if (anchor != null && !columns.contains(anchor)) {
                    缺列的表.add(table + "（append-only，缺时间锚点 " + anchor + "）");
                }
                continue;
            }
            if (!columns.containsAll(List.of("created_at", "created_by", "updated_at", "updated_by"))) {
                缺列的表.add(table);
            }
        }
        assertThat(缺列的表).as("缺审计四列的表").isEmpty();
    }

    @Test
    @DisplayName("TC-v0.1-024 主键名为 id 且为 bigint，时间列为 datetime(3)")
    void 命名与类型规范() throws Exception {
        List<String> 违规 = new ArrayList<>();
        for (String table : 表名清单()) {
            if (!"id".equals(主键列(table))) {
                违规.add(table + ": 主键不是 id");
            }
            boolean 时间列合规;
            if (!APPEND_ONLY.contains(table)) {
                时间列合规 = "datetime(3)".equalsIgnoreCase(列类型(table, "created_at"))
                        && "datetime(3)".equalsIgnoreCase(列类型(table, "updated_at"));
            } else if (CREATED_AT_REQUIRED.contains(table)) {
                时间列合规 = "datetime(3)".equalsIgnoreCase(列类型(table, "created_at"));
            } else {
                时间列合规 = "datetime(3)".equalsIgnoreCase(列类型(table, TIME_ANCHORS.get(table)));
            }
            if (!时间列合规) {
                违规.add(table + ": 时间列不是 datetime(3)");
            }
        }
        assertThat(违规).isEmpty();
    }

    @Test
    @DisplayName("TC-v0.1-028 全表字符集为 utf8mb4")
    void 字符集() throws Exception {
        assertThat(字符集清单()).containsOnly("utf8mb4");
    }

    @Test
    @DisplayName("TC-v0.1-025 / 026 run_id 与 resource_key 的唯一约束真的生效")
    void 关键唯一约束() throws Exception {
        try (Connection conn = 连接(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO project(code,name) VALUES ('IT-P','集成测试项目')");
            st.execute("INSERT INTO test_set(project_id,set_code,name) "
                    + "SELECT id,'IT-S','集成测试集' FROM project WHERE code='IT-P'");
            st.execute("INSERT INTO run(run_id,project_id,test_set_id) "
                    + "SELECT 'IT-RUN-1',p.id,s.id FROM project p JOIN test_set s ON s.project_id=p.id "
                    + "WHERE p.code='IT-P' AND s.set_code='IT-S'");
            assertThat(插入重复runId失败()).isTrue();

            st.execute("INSERT INTO execution_job(project_id,job_no) "
                    + "SELECT id,'IT-JOB-1' FROM project WHERE code='IT-P'");
            st.execute("INSERT INTO resource_lease(job_id,resource_key) "
                    + "SELECT id,'GPU-01#mlx5_0' FROM execution_job WHERE job_no='IT-JOB-1'");
            assertThat(插入重复租约失败()).isTrue();
        } finally {
            // 本类用的是专用 kek 库，直接 clean 即可复位，不污染其他库
            flyway.clean();
            flyway.migrate();
        }
    }

    @Test
    @DisplayName("TC-AST-05-01 资产池结构：三表 owner_scope、project_id 可空、引用表与平台序列号唯一")
    void 资产池结构() throws Exception {
        for (String table : List.of("device", "link", "credential")) {
            assertThat(列名(table)).as(table + " 缺少 owner_scope").contains("owner_scope");
            assertThat(可空列(table, "project_id")).as(table + " 的 project_id 必须可空").isTrue();
        }
        assertThat(表名清单()).as("缺 project_asset").contains("project_asset");
        assertThat(索引名("project_asset")).as("引用唯一键").contains("uk_project_asset");
        assertThat(索引名("device")).as("平台级序列号唯一键").contains("uk_device_serial");
    }

    @Test
    @DisplayName("TC-AST-05-02 用例不与设备耦合：test_case 不得出现 device 类列（DEV-PLAN §10.8 步骤 7）")
    void 用例不绑定设备() throws Exception {
        assertThat(列名("test_case")).as("用例只能声明能力/技术域，DUT 由执行时选")
                .noneMatch(column -> column.startsWith("device"));
    }

    // ---------------------------------------------------------------- 内部工具

    private Connection 连接() throws Exception {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String user = System.getenv().getOrDefault("DB_USERNAME", "robot");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        return java.sql.DriverManager.getConnection(
                "jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME, user, password);
    }

    private List<String> 表名清单() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection conn = 连接();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT table_name FROM information_schema.tables "
                             + "WHERE table_schema='" + DB_NAME + "' AND table_name<>'flyway_schema_history' "
                             + "ORDER BY table_name")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        return tables;
    }

    private List<String> 列名(String table) throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection conn = 连接();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT column_name FROM information_schema.columns WHERE table_schema='"
                             + DB_NAME + "' AND table_name='" + table + "'")) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    private boolean 可空列(String table, String column) throws Exception {
        try (Connection conn = 连接();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT is_nullable FROM information_schema.columns WHERE table_schema='"
                             + DB_NAME + "' AND table_name='" + table + "' AND column_name='" + column + "'")) {
            return rs.next() && "YES".equalsIgnoreCase(rs.getString(1));
        }
    }

    private List<String> 索引名(String table) throws Exception {
        List<String> indexes = new ArrayList<>();
        try (Connection conn = 连接();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema='"
                             + DB_NAME + "' AND table_name='" + table + "'")) {
            while (rs.next()) {
                indexes.add(rs.getString(1));
            }
        }
        return indexes;
    }

    private String 列类型(String table, String column) throws Exception {
        try (Connection conn = 连接();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT column_type FROM information_schema.columns WHERE table_schema='"
                             + DB_NAME + "' AND table_name='" + table + "' AND column_name='" + column + "'")) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    private String 主键列(String table) throws Exception {
        try (Connection conn = 连接();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT column_name FROM information_schema.key_column_usage WHERE table_schema='"
                             + DB_NAME + "' AND table_name='" + table + "' AND constraint_name='PRIMARY'")) {
            return rs.next() ? rs.getString(1) : "";
        }
    }

    private List<String> 字符集清单() throws Exception {
        List<String> collations = new ArrayList<>();
        try (Connection conn = 连接();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT DISTINCT table_collation FROM information_schema.tables WHERE table_schema='"
                             + DB_NAME + "' AND table_name<>'flyway_schema_history'")) {
            while (rs.next()) {
                collations.add(rs.getString(1).substring(0, rs.getString(1).indexOf('_')));
            }
        }
        return collations;
    }

    private boolean 插入重复runId失败() throws Exception {
        try (Connection conn = 连接(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO run(run_id,project_id) "
                    + "SELECT 'IT-RUN-1',id FROM project WHERE code='IT-P'");
            return false;
        } catch (java.sql.SQLException ex) {
            return ex.getMessage().toLowerCase().contains("duplicate");
        }
    }

    private boolean 插入重复租约失败() throws Exception {
        try (Connection conn = 连接(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO resource_lease(job_id,resource_key) "
                    + "SELECT id,'GPU-01#mlx5_0' FROM execution_job WHERE job_no='IT-JOB-1'");
            return false;
        } catch (java.sql.SQLException ex) {
            return ex.getMessage().toLowerCase().contains("duplicate");
        }
    }
}
