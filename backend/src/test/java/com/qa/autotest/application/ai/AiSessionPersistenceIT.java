package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.infrastructure.AiSessionRepositoryJdbc;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * TC-AI-03：会话 id 全局唯一且持久化；模拟进程重启后新 id 不与历史重复。
 * 真库验收（robot_it），需 -Pit 与 MariaDB；只写 ai_session，不起子进程。
 */
@Tag("it")
class AiSessionPersistenceIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");

    @Test
    void idsAreUniqueAcrossRestart() {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误清开发库").endsWith("_it");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        AiSessionRepositoryJdbc repository = new AiSessionRepositoryJdbc(jdbc);
        AiPanelService first = AiTestSupport.service(AiTestSupport.stubProbe(false),
                AiFeatureGate.permissive(), new AiProcessManager(), repository);

        long a = first.open(1L, 2L);
        long b = first.open(1L, 2L);
        assertThat(a).isNotEqualTo(b);

        // 模拟进程重启：同一个库、全新的服务实例，不再依赖内存计数
        AiPanelService restarted = AiTestSupport.service(AiTestSupport.stubProbe(false),
                AiFeatureGate.permissive(), new AiProcessManager(), repository);
        long c = restarted.open(1L, 2L);
        assertThat(c).isNotIn(a, b);

        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_session WHERE id IN (?, ?, ?)", Long.class, a, b, c);
        assertThat(rows).isEqualTo(3L);
        Long uuidRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_session WHERE id IN (?, ?, ?) AND session_uuid IS NOT NULL",
                Long.class, a, b, c);
        assertThat(uuidRows).isEqualTo(3L);
    }

    private static DataSource dataSource() {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("DB_PORT", "3306");
        String user = System.getenv().getOrDefault("DB_USERNAME", "robot");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + DB_NAME
                + "?useUnicode=true&characterEncoding=utf8";
        return new DriverManagerDataSource(url, user, password);
    }
}
