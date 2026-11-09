package com.qa.autotest.domain.ai;

/** 提示词模板（FR-3.3-03）。projectId=0 表示平台内置；项目自定义覆盖同键内置模板。 */
public record PromptTemplate(long id, long projectId, String key, String name, String body,
                             int version, boolean builtin, boolean enabled, String updatedAt) {
}
