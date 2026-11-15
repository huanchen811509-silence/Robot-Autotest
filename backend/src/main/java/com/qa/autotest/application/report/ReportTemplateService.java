package com.qa.autotest.application.report;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ReportTemplateRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.report.ReportTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * 报告模板管理（TPLX-01，v2.1）。
 *
 * <p>职责边界：模板只决定「对外报告导出哪些字段、什么顺序、什么标题」，
 * **不改变字段白名单**——白名单是 {@link ReportFieldWhitelist}，模板只能在里面挑。
 * 三道校验都在这里做（接口层不重复判），前端那一半「不可选」由 `/fields` 返回的清单驱动。
 *
 * <p>权限与报告读一致：{@code EXECUTION:READ} 看，{@code EXECUTION:UPDATE} 改。
 */
@Service
public class ReportTemplateService {

    private final ReportTemplateRepository templates;
    private final AccessControlService access;
    private final AuditService audit;

    public ReportTemplateService(ReportTemplateRepository templates,
                                 AccessControlService access, AuditService audit) {
        this.templates = templates;
        this.access = access;
        this.audit = audit;
    }

    /** 提交给服务的模板内容：字段顺序 = 列表顺序，`sortOrder` 由服务端重排。 */
    public record TemplateCommand(long projectId, String name, String description,
                                  List<FieldCommand> fields, String kernel) {

        /** 兼容 v5.14.0 之前的 4 参写法（旧客户端不传 kernel → robot）。 */
        public TemplateCommand(long projectId, String name, String description,
                               List<FieldCommand> fields) {
            this(projectId, name, description, fields, ReportTemplate.KERNEL_ROBOT);
        }
    }

    /** kernel 只允许三个值；非法值显式 400，不静默回落成 robot（默认拒绝）。 */
    public static String requireKernel(String raw) {
        if (!ReportTemplate.validKernel(raw)) {
            throw bad("kernel 只能是 robot / pytest / any: " + raw);
        }
        return ReportTemplate.normalizeKernel(raw);
    }

    public record FieldCommand(String fieldKey, String titleOverride) {
    }

    public List<ReportTemplateView> list(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return templates.listVisible(projectId).stream().map(ReportTemplateView::of).toList();
    }

    /**
     * 前端勾选项的**唯一来源**。前端不许内联一份字段名单：
     * 内联了就是第二份白名单，白名单收紧时界面还能选到已禁用的字段。
     */
    public List<String> selectableFields() {
        return selectableFields(null);
    }

    /**
     * v5.14.0 / W64-12：按内核给可选字段，仍只来自 ReportFieldWhitelist 一个真源。
     *
     * <p>W7 真实 HTTP 探针修复：读路径原本直接把入参交给白名单，非法值静默回落成
     * 21 项 RF 字段（fail-open），而写路径 {@link #requireKernel} 是严格 400。
     * 现在两条路径共用同一份校验：null / 空白 = 旧客户端默认 robot，其它值必须合法。
     */
    public List<String> selectableFields(String kernel) {
        return ReportFieldWhitelist.selectable(selectableKernel(kernel));
    }

    /** 读路径 kernel 校验的单一真值（静态方法便于零依赖单测）。 */
    public static String selectableKernel(String raw) {
        if (raw == null || raw.isBlank()) {
            return ReportTemplate.KERNEL_ROBOT;
        }
        return requireKernel(raw);
    }

    public ReportTemplateView get(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return ReportTemplateView.of(require(projectId, id));
    }

    @Transactional
    public ReportTemplateView create(TemplateCommand command) {
        long projectId = requireProject(command);
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);

        String name = requireName(command.name());
        String description = normalizeDescription(command.description());
        List<ReportTemplate.Field> fields = normalizeFields(command.fields());
        String kernel = requireKernel(command.kernel());

        Optional<ReportTemplate> sameName = templates.findByName(projectId, name);
        if (sameName.isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "同项目下已存在同名模板: " + name);
        }

        ReportTemplate draft = new ReportTemplate(0L, projectId, name, description, 1,
                false, fields, null, null, kernel);
        long id = templates.insert(draft, null);
        // 必须显式写字段：`insert` 只落主表一行（字段是另一张表）。
        // 漏这一步时单测仍然全绿——伪仓储把 draft.fields 一起存了，把 bug 盖住了；
        // 是 TPLX-01 的真实 HTTP 验收（TPLX01-05「字段数=0」）把它抓出来的。
        templates.replaceFields(projectId, id, fields, null);
        audit.record("REPORT_TEMPLATE_CREATE", "report_template", String.valueOf(id),
                "name=" + name + "; fields=" + fields.size() + "; kernel=" + kernel);
        return ReportTemplateView.of(require(projectId, id));
    }

    /**
     * v5.14.0 / W64-17：把可见模板（平台内置或本项目）复制成**本项目可改**的模板。
     *
     * <p>四条边界：① 源只读、不改动任何一行；② 副本是独立主表行 + 独立字段行，
     * 漏了 replaceFields 会得到一个 0 字段的副本（TPLX-01 真实 HTTP 抓到过同类问题）；
     * ③ 名称确定性顺延，不覆盖已有同名模板；④ 权限口径与 create 完全一致
     * （EXECUTION:UPDATE），非管理员无该权限时被拒 —— 不另造一条更宽的通道。
     */
    @Transactional
    public ReportTemplateView copy(long projectId, long id) {
        requireProjectId(projectId);
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);

        ReportTemplate source = require(projectId, id);
        List<ReportTemplate.Field> fields = source.fields() == null ? List.of()
                : source.fields().stream()
                        .map(f -> new ReportTemplate.Field(
                                f.fieldKey(), f.sortOrder(), f.titleOverride()))
                        .toList();
        if (fields.isEmpty()) {
            throw bad("源模板没有字段，无法复制: " + id);
        }

        String name = copyName(projectId, source.name());
        ReportTemplate draft = new ReportTemplate(0L, projectId, name, source.description(), 1,
                false, fields, null, null, source.kernel());
        long newId = templates.insert(draft, null);
        templates.replaceFields(projectId, newId, fields, null);
        audit.record("REPORT_TEMPLATE_COPY", "report_template", String.valueOf(newId),
                "source=" + id + "; name=" + name + "; fields=" + fields.size()
                        + "; kernel=" + source.kernel());
        return ReportTemplateView.of(require(projectId, newId));
    }

    /** 副本名：原名 + "-副本"，同名时 -2 / -3 顺延；超长按 MAX_NAME_LENGTH 截断。 */
    private String copyName(long projectId, String sourceName) {
        String suffix = "-副本";
        int max = ReportTemplate.MAX_NAME_LENGTH;
        String base = sourceName == null ? "TEMPLATE" : sourceName.trim();
        if (base.length() + suffix.length() > max) {
            base = base.substring(0, max - suffix.length());
        }
        for (int i = 1; i <= 100; i++) {
            String candidate = base + suffix + (i == 1 ? "" : "-" + i);
            if (candidate.length() > max) {
                candidate = candidate.substring(0, max);
            }
            if (templates.findByName(projectId, candidate).isEmpty()) {
                return requireName(candidate);
            }
        }
        throw bad("同名模板过多，无法生成副本名: " + sourceName);
    }

    private static void requireProjectId(long projectId) {
        if (projectId <= 0) {
            throw bad("projectId 不能为空");
        }
    }

    @Transactional
    public ReportTemplateView update(long id, TemplateCommand command) {
        long projectId = requireProject(command);
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);

        ReportTemplate existing = require(projectId, id);
        requireEditable(existing);

        String name = requireName(command.name());
        String description = normalizeDescription(command.description());
        List<ReportTemplate.Field> fields = normalizeFields(command.fields());

        Optional<ReportTemplate> sameName = templates.findByName(projectId, name);
        if (sameName.isPresent() && sameName.get().id() != id) {
            throw new BusinessException(ErrorCode.CONFLICT, "同项目下已存在同名模板: " + name);
        }

        templates.update(projectId, id, name, description, null);
        templates.replaceFields(projectId, id, fields, null);
        audit.record("REPORT_TEMPLATE_UPDATE", "report_template", String.valueOf(id),
                "name=" + name + "; fields=" + fields.size()
                        + "; version=" + (existing.version() + 1));
        return ReportTemplateView.of(require(projectId, id));
    }

    @Transactional
    public void delete(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);
        ReportTemplate existing = require(projectId, id);
        requireEditable(existing);
        templates.delete(projectId, id);
        audit.record("REPORT_TEMPLATE_DELETE", "report_template", String.valueOf(id),
                "name=" + existing.name());
    }

    /**
     * 设为默认模板。平台级模板不能被设为「项目默认」以外的语义：
     * 这里只允许项目级模板参与，平台级本身已经是全局回落值。
     */
    @Transactional
    public ReportTemplateView setDefault(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.UPDATE);
        ReportTemplate existing = require(projectId, id);
        requireEditable(existing);
        templates.markDefault(projectId, id);
        audit.record("REPORT_TEMPLATE_SET_DEFAULT", "report_template", String.valueOf(id),
                "name=" + existing.name());
        return ReportTemplateView.of(require(projectId, id));
    }

    /**
     * 字段校验，四道，顺序不能反：
     * ① 空模板无意义；② 白名单（最常错，先报它）；③ 重复；④ 必填缺失。
     *
     * <p>`sortOrder` 一律由服务端按提交顺序重排：客户端传的顺序值如果被采信，
     * 前端拖排出现「两个字段同一个序号」时会渲染成不确定顺序。
     */
    List<ReportTemplate.Field> normalizeFields(List<FieldCommand> input) {
        if (input == null || input.isEmpty()) {
            throw bad("模板至少要有一个字段");
        }
        if (input.size() > ReportTemplate.MAX_FIELDS) {
            throw bad("字段数超过上限 " + ReportTemplate.MAX_FIELDS + ": " + input.size());
        }

        List<String> keys = new ArrayList<>(input.size());
        for (FieldCommand field : input) {
            String key = field == null || field.fieldKey() == null ? "" : field.fieldKey().trim();
            if (key.isEmpty()) {
                throw bad("字段 key 不能为空");
            }
            keys.add(key);
        }

        List<String> illegal = ReportFieldWhitelist.illegal(keys);
        if (!illegal.isEmpty()) {
            throw bad("字段不在 C5 白名单内: " + join(illegal)
                    + "；可选字段见 GET /api/report-templates/fields");
        }

        List<String> duplicated = duplicates(keys);
        if (!duplicated.isEmpty()) {
            throw bad("字段重复: " + join(duplicated));
        }

        List<String> missing = new ArrayList<>();
        for (String required : ReportFieldWhitelist.REQUIRED_FIELDS) {
            if (!keys.contains(required)) {
                missing.add(required);
            }
        }
        if (!missing.isEmpty()) {
            throw bad("模板缺少必填字段: " + join(missing));
        }

        List<ReportTemplate.Field> fields = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            String override = input.get(i).titleOverride();
            String title = override == null ? null : override.trim();
            if (title != null && title.length() > ReportTemplate.MAX_TITLE_OVERRIDE_LENGTH) {
                throw bad("字段标题超过 " + ReportTemplate.MAX_TITLE_OVERRIDE_LENGTH + " 字符: " + keys.get(i));
            }
            fields.add(new ReportTemplate.Field(keys.get(i), i, title == null || title.isEmpty() ? null : title));
        }
        return fields;
    }

    private ReportTemplate require(long projectId, long id) {
        Optional<ReportTemplate> found = templates.find(projectId, id);
        if (found.isPresent()) {
            return found.get();
        }
        // 区分 403 / 404：越权和「id 写错」对调用方是两件事
        if (templates.existsInOtherProject(projectId, id)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "模板属于其它项目: " + id);
        }
        throw new BusinessException(ErrorCode.NOT_FOUND, "模板不存在: " + id);
    }

    /** 平台级模板是迁移内置的只读模板：改了会影响所有项目，本版不给改。 */
    private static void requireEditable(ReportTemplate template) {
        if (template.platform()) {
            throw bad("平台级模板为内置模板，本版不可修改；请复制为项目模板后再改");
        }
    }

    private static long requireProject(TemplateCommand command) {
        long projectId = command == null ? 0L : command.projectId();
        if (projectId <= 0) {
            throw bad("projectId 不能为空");
        }
        return projectId;
    }

    private static String requireName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw bad("模板名称不能为空");
        }
        if (name.length() > ReportTemplate.MAX_NAME_LENGTH) {
            throw bad("模板名称超过 " + ReportTemplate.MAX_NAME_LENGTH + " 字符");
        }
        return name;
    }

    private static String normalizeDescription(String raw) {
        String description = raw == null ? "" : raw.trim();
        if (description.length() > ReportTemplate.MAX_DESCRIPTION_LENGTH) {
            throw bad("模板描述超过 " + ReportTemplate.MAX_DESCRIPTION_LENGTH + " 字符");
        }
        return description;
    }

    private static List<String> duplicates(List<String> keys) {
        List<String> seen = new ArrayList<>();
        List<String> dup = new ArrayList<>();
        for (String key : keys) {
            if (seen.contains(key)) {
                if (!dup.contains(key)) {
                    dup.add(key);
                }
            } else {
                seen.add(key);
            }
        }
        return dup;
    }

    private static String join(List<String> values) {
        StringJoiner joiner = new StringJoiner(", ");
        values.forEach(joiner::add);
        return joiner.toString();
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.INVALID_ARGUMENT, message);
    }
}
