package com.qa.autotest.application.repository;

import com.qa.autotest.domain.ai.PromptTemplate;
import java.util.List;
import java.util.Optional;

/** 提示词库（FR-3.3-03）。内置模板 project_id=0；项目模板覆盖同键内置模板。 */
public interface AiPromptRepository {

    List<PromptTemplate> list(long projectId);

    Optional<PromptTemplate> find(long projectId, String key);

    PromptTemplate save(long projectId, String key, String name, String body, Long actorId);

    boolean delete(long projectId, String key);
}
