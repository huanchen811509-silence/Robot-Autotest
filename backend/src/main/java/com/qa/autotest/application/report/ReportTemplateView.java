package com.qa.autotest.application.report;

import com.qa.autotest.domain.report.ReportTemplate;

import java.util.List;

/**
 * 模板视图（TPLX-01）。与实体分开的理由：实体带 `updatedBy` 之类的内部列，
 * 对外只需要「叫什么、有哪些字段、是不是默认」。
 */
public record ReportTemplateView(long id, long projectId, String name, String description,
                                 int version, boolean isDefault, boolean platform,
                                 List<FieldView> fields, String createdAt, String kernel) {

    /** 一项字段：key 给机器用，title 给界面用（自定义标题优先）。 */
    public record FieldView(String fieldKey, int sortOrder, String titleOverride) {
    }

    public static ReportTemplateView of(ReportTemplate template) {
        return new ReportTemplateView(
                template.id(),
                template.projectId(),
                template.name(),
                template.description() == null ? "" : template.description(),
                template.version(),
                template.isDefault(),
                template.platform(),
                template.fields().stream()
                        .map(f -> new FieldView(f.fieldKey(), f.sortOrder(), f.titleOverride()))
                        .toList(),
                template.createdAt() == null ? "" : template.createdAt().toString(),
                template.kernel());
    }
}
