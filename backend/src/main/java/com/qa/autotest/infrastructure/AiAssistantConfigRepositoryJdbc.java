package com.qa.autotest.infrastructure;

import com.qa.autotest.application.repository.AiAssistantConfigRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * AI 助手配置仓储 JDBC 实现（v2.5 / FR-AI-10）。
 *
 * <p>写入用 {@code INSERT ... ON DUPLICATE KEY UPDATE}（唯一键 {@code cli_key}）：
 * 页面每次保存都是一次「upsert」，不需要前端先判断存在与否。
 *
 * <p>两种写入语义分开两条 SQL，而不是在 SQL 里用 {@code COALESCE} 绕：
 * {@code api_key_enc = NULL} 与「保持原值」是两件事，用 {@code COALESCE} 混在一起
 * 会导致「清空密钥」永远清不掉。
 */
@Repository
public class AiAssistantConfigRepositoryJdbc implements AiAssistantConfigRepository {

    private static final String SELECT_ALL = """
            SELECT cli_key, base_url, api_key_enc, model, enabled,
                   DATE_FORMAT(updated_at, '%Y-%m-%d %H:%i:%s') AS updated_at
              FROM ai_assistant_config
            """;

    private static final String UPSERT_WITH_KEY = """
            INSERT INTO ai_assistant_config
                (cli_key, base_url, api_key_enc, model, enabled, updated_by)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                base_url = VALUES(base_url),
                api_key_enc = VALUES(api_key_enc),
                model = VALUES(model),
                enabled = VALUES(enabled),
                updated_by = VALUES(updated_by)
            """;

    private static final String UPSERT_WITHOUT_KEY = """
            INSERT INTO ai_assistant_config
                (cli_key, base_url, model, enabled, updated_by)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                base_url = VALUES(base_url),
                model = VALUES(model),
                enabled = VALUES(enabled),
                updated_by = VALUES(updated_by)
            """;

    private final JdbcTemplate jdbc;

    public AiAssistantConfigRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Row> findAll() {
        Map<String, Row> result = new LinkedHashMap<>();
        jdbc.query(SELECT_ALL, rs -> {
            Row row = new Row(
                    rs.getString("cli_key"),
                    rs.getString("base_url"),
                    rs.getString("api_key_enc"),
                    rs.getString("model"),
                    rs.getBoolean("enabled"),
                    rs.getString("updated_at"));
            result.put(row.cliKey(), row);
        });
        return result;
    }

    @Override
    public void save(Row row, boolean replaceKey, Long userId) {
        if (replaceKey) {
            jdbc.update(UPSERT_WITH_KEY, row.cliKey(), row.baseUrl(), row.apiKeyEnc(),
                    row.model(), row.enabled(), userId);
        } else {
            jdbc.update(UPSERT_WITHOUT_KEY, row.cliKey(), row.baseUrl(), row.model(),
                    row.enabled(), userId);
        }
    }
}
