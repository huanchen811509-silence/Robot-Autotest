package com.qa.autotest.domain.report;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 报告模板（TPLX-01，v2.1）：一份「对外报告该有哪些字段、按什么顺序、叫什么名字」的配置。
 *
 * <p>两个刻意选择：
 * <ol>
 *   <li><b>字段存 key 不存下标</b>：白名单（{@code ReportFieldWhitelist}）一旦调整顺序，
 *       存下标的模板会静默指到别的字段；存 key 至少能在校验时发现「这个键已经不在白名单了」。</li>
 *   <li><b>平台级模板用 {@code projectId == 0} 表示</b>：与 V2_5 编辑器关键词同一套约定，
 *       不新造 `is_platform` 标志。本版平台级模板只读（由迁移内置 DEFAULT 一份），
 *       项目级模板才可增删改 —— 否则 A 项目改一下会影响 B 项目的导出。</li>
 * </ol>
 */
public record ReportTemplate(long id, long projectId, String name, String description,
                             int version, boolean isDefault,
                             List<Field> fields, Long createdBy, Instant createdAt,
                             /** v5.14.0：适用内核 robot / pytest / any。 */
                             String kernel) {

    public ReportTemplate {
        kernel = normalizeKernel(kernel);
    }

    /** 兼容 v5.14.0 之前的 9 参写法：存量模板一律按 robot 处理，行为不变。 */
    public ReportTemplate(long id, long projectId, String name, String description,
                          int version, boolean isDefault, List<Field> fields,
                          Long createdBy, Instant createdAt) {
        this(id, projectId, name, description, version, isDefault, fields,
                createdBy, createdAt, KERNEL_ROBOT);
    }

    /** 模板里的一项字段：key + 顺序 + 可选的自定义标题。 */
    public record Field(String fieldKey, int sortOrder, String titleOverride) {
    }

    public static final String KERNEL_ROBOT = "robot";
    public static final String KERNEL_PYTEST = "pytest";
    public static final String KERNEL_ANY = "any";

    /** 归一化并兜底：读库遇到历史脏值时不让它变成「第五种内核」。 */
    public static String normalizeKernel(String raw) {
        if (raw == null || raw.isBlank()) {
            return KERNEL_ROBOT;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.equals(KERNEL_ROBOT) || value.equals(KERNEL_PYTEST)
                || value.equals(KERNEL_ANY) ? value : KERNEL_ROBOT;
    }

    public static boolean validKernel(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.equals(KERNEL_ROBOT) || value.equals(KERNEL_PYTEST)
                || value.equals(KERNEL_ANY);
    }

    /** 平台级模板的 project_id 约定值。 */
    public static final long PLATFORM_PROJECT_ID = 0L;

    public static final int MAX_NAME_LENGTH = 128;
    public static final int MAX_DESCRIPTION_LENGTH = 512;
    public static final int MAX_TITLE_OVERRIDE_LENGTH = 128;
    public static final int MAX_FIELDS = 32;

    public boolean platform() {
        return projectId == PLATFORM_PROJECT_ID;
    }

    /** 字段 key 列表（按 sortOrder），用于 diff / 校验。 */
    public List<String> fieldKeys() {
        return fields.stream().map(Field::fieldKey).toList();
    }
}
