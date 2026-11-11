package com.qa.autotest.application.iam;

import java.util.Locale;

/**
 * 从模板建项目入参（v4.9.2 / OPT-06 / FR-49-22）。
 *
 * <p>与既有 {@code CreateProjectRequest} 完全独立，互不影响 {@code POST /api/projects}。
 * {@code code} 可选；{@code mode} 缺省/空白一律归一为 {@code copy}（用单测锁死该默认值），
 * 仅额外允许 {@code reference}。
 */
public record CreateProjectFromTemplateRequest(Long templateId, String name, String code, String mode) {

    public static final String MODE_COPY = "copy";
    public static final String MODE_REFERENCE = "reference";

    public CreateProjectFromTemplateRequest {
        if (mode == null || mode.isBlank()) {
            mode = MODE_COPY;
        } else {
            mode = mode.trim().toLowerCase(Locale.ROOT);
        }
    }

    /** 防御性归一：即使经反射绕过紧凑构造，也不会把 null 模式传进编排。 */
    public String resolvedMode() {
        return (mode == null || mode.isBlank()) ? MODE_COPY : mode;
    }
}
