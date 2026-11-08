package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.domain.ai.AiActionResult;
import com.qa.autotest.infrastructure.AiSessionRepositoryJdbc;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * TC-AI-04：一轮 suggest + execute + state 后 ai_session / ai_action / ai_cli_probe 各 ≥1 行，
 * 且 ai_action.summary 与响应一致。真库验收（robot_it），需 -Pit 与 MariaDB。
 * 不启用 FEATURE_AI_REAL_CLI，只验落库，不起子进程。
 */
@Tag("it")
class AiTablesPersistIT {

    private static final String DB_NAME = System.getenv().getOrDefault("DB_NAME", "robot_it");

    @Test
    void threeTablesGetRowsAfterOneRound() {
        assertThat(DB_NAME).as("集成测试库名必须以 _it 结尾，防止误清开发库").endsWith("_it");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        AiSessionRepositoryJdbc repository = new AiSessionRepositoryJdbc(jdbc);
        AiPanelService service = AiTestSupport.service(AiTestSupport.stubProbe(true),
                AiFeatureGate.permissive(), new AiProcessManager(), repository);

        long session = service.open(1L, 2L);
        service.state(1L);
        AiActionResult suggested = service.suggest(session, Map.of("runId", "RUN-IT-1"));
        AiActionResult executed = service.execute(session, "run-1", true);
        service.evaluate(session, 1, 0);

        assertThat(count(jdbc, "ai_session")).isGreaterThanOrEqualTo(1L);
        assertThat(count(jdbc, "ai_action")).isGreaterThanOrEqualTo(3L);
        assertThat(count(jdbc, "ai_cli_probe")).isGreaterThanOrEqualTo(4L);

        String storedSuggest = jdbc.queryForObject(
                "SELECT summary FROM ai_action WHERE session_id = ? AND action = 'SUGGEST' "
                        + "ORDER BY id DESC LIMIT 1", String.class, session);
        assertThat(storedSuggest).isEqualTo(suggested.summary());

        String storedExecute = jdbc.queryForObject(
                "SELECT summary FROM ai_action WHERE session_id = ? AND action = 'EXECUTE' "
                        + "ORDER BY id DESC LIMIT 1", String.class, session);
        assertThat(storedExecute).isEqualTo(executed.summary());

        Long openRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ai_session WHERE id = ? AND status = 'OPEN'",
                Long.class, session);
        assertThat(openRows).isEqualTo(1L);
    }

    private static long count(JdbcTemplate jdbc, String table) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
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
