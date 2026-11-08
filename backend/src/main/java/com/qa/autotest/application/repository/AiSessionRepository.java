package com.qa.autotest.application.repository;

/** AI 会话 / 动作 / CLI 探测的持久化契约（FR-AI-03 / FR-AI-04）。 */
public interface AiSessionRepository {

    /** 写 ai_session，返回数据库自增主键（全局唯一、重启不复用）。 */
    long openSession(long projectId, long userId, String sessionUuid);

    void closeSession(long sessionId);

    void recordAction(long sessionId, String action, String level, boolean confirmed,
                       String result, String summary, String stdout, String stderr,
                       int exitCode, long durationMs, String cli, boolean timedOut);

    void recordProbe(String cliName, boolean available, boolean nonInteractive);
}
