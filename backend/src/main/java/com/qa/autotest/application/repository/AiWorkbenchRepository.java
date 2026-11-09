package com.qa.autotest.application.repository;

import java.time.Instant;
import java.util.List;

/** AI 工作台持久化契约（FR-3.3-07）：项目配置 / 会话消息 / 配额计数。 */
public interface AiWorkbenchRepository {

    /** 项目配置；未配置时返回默认值，不写库。 */
    record Config(boolean enabled, int dailyQuota, String defaultAgent, String defaultModel,
                  int maxContextChars) {
        public static Config defaults() {
            return new Config(true, 200, null, null, 4000);
        }
    }

    record SessionRow(long id, String status, String title, int messageCount, String lastPreview,
                      Instant createdAt) {
    }

    record MessageRow(long id, String role, String content, String citationsJson, String mode,
                      Instant createdAt) {
    }

    Config config(long projectId);

    Config saveConfig(long projectId, Config config, Long actorId);

    /** 当日已消耗动作数：ai_action 经 ai_session 汇总到项目。 */
    long countActionsSince(long projectId, Instant since);

    long appendMessage(long projectId, long sessionId, String role, String content,
                       String citationsJson, String mode, String traceId);

    List<SessionRow> sessions(long projectId, long userId, int limit);

    List<MessageRow> messages(long projectId, long sessionId);

    boolean sessionBelongs(long projectId, long sessionId);
}
