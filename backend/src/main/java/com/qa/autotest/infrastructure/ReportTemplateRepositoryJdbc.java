package com.qa.autotest.infrastructure;

import com.qa.autotest.application.report.TemplateSelection;
import com.qa.autotest.application.repository.ReportTemplateRepository;
import com.qa.autotest.common.tenant.TenantGuard;
import com.qa.autotest.domain.report.ReportTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 报告模板仓储（TPLX-01）。字段单独一张表，随模板级联删除。 */
@Repository
public class ReportTemplateRepositoryJdbc implements ReportTemplateRepository {

    private static final RowMapper<ReportTemplate> MAPPER = (rs, rowNum) -> new ReportTemplate(
            rs.getLong("id"),
            rs.getLong("project_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getInt("version"),
            rs.getBoolean("is_default"),
            List.of(),
            rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
            JdbcSupport.instant(rs.getTimestamp("created_at")),
            rs.getString("kernel"));

    private static final String SELECT = """
            SELECT id, project_id, name, description, version, is_default, kernel,
                   created_by, created_at
              FROM report_template
            """;

    private static final String SELECT_FIELDS = """
            SELECT field_key, sort_order, title_override
              FROM report_template_field
             WHERE project_id = ? AND template_id = ?
             ORDER BY sort_order ASC, id ASC
            """;

    private static final String INSERT = """
            INSERT INTO report_template (project_id, name, description, version, is_default, kernel)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_FIELD = """
            INSERT INTO report_template_field (project_id, template_id, field_key, sort_order,
                                               title_override, created_by)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public ReportTemplateRepositoryJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReportTemplate> listVisible(long projectId) {
        // 平台级（project_id = 0）在前：它是默认值来源，界面按这个顺序展示更自然
        String sql = SELECT + " WHERE project_id IN (?, 0) ORDER BY project_id ASC, id ASC";
        TenantGuard.requireProjectScope(sql, "report_template");
        List<ReportTemplate> templates = jdbc.query(sql, MAPPER, projectId);
        List<ReportTemplate> withFields = new ArrayList<>(templates.size());
        for (ReportTemplate template : templates) {
            withFields.add(withFields(template));
        }
        return withFields;
    }

    @Override
    public Optional<ReportTemplate> find(long projectId, long id) {
        String sql = SELECT + " WHERE project_id IN (?, 0) AND id = ?";
        TenantGuard.requireProjectScope(sql, "report_template");
        return jdbc.query(sql, MAPPER, projectId, id).stream().findFirst().map(this::withFields);
    }

    @Override
    public Optional<ReportTemplate> findByName(long projectId, String name) {
        String sql = SELECT + " WHERE project_id = ? AND name = ? ORDER BY id ASC";
        TenantGuard.requireProjectScope(sql, "report_template");
        return jdbc.query(sql, MAPPER, projectId, name).stream().findFirst().map(this::withFields);
    }

    @Override
    public boolean existsInOtherProject(long projectId, long id) {
        String sql = "SELECT COUNT(*) FROM report_template WHERE id = ? AND project_id <> ? AND project_id <> 0";
        TenantGuard.requireProjectScope(sql, "report_template");
        Integer count = jdbc.queryForObject(sql, Integer.class, id, projectId);
        return count != null && count > 0;
    }

    @Override
    public long insert(ReportTemplate draft, Long operatorId) {
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, draft.projectId());
            ps.setString(2, draft.name());
            ps.setString(3, draft.description());
            ps.setInt(4, draft.version());
            ps.setBoolean(5, draft.isDefault());
            ps.setString(6, draft.kernel());
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) {
            throw new IllegalStateException("report_template 插入未返回主键");
        }
        return key.longValue();
    }

    @Override
    public void update(long projectId, long id, String name, String description, Long operatorId) {
        String sql = """
                UPDATE report_template
                   SET name = ?, description = ?, version = version + 1, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """;
        TenantGuard.requireProjectScope(sql, "report_template");
        jdbc.update(sql, name, description, operatorId, projectId, id);
    }

    @Override
    public void delete(long projectId, long id) {
        String sql = "DELETE FROM report_template WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(sql, "report_template");
        jdbc.update(sql, projectId, id);
    }

    @Override
    public void replaceFields(long projectId, long templateId, List<ReportTemplate.Field> fields,
                              Long operatorId) {
        String del = "DELETE FROM report_template_field WHERE project_id = ? AND template_id = ?";
        TenantGuard.requireProjectScope(del, "report_template_field");
        jdbc.update(del, projectId, templateId);
        for (ReportTemplate.Field field : fields) {
            jdbc.update(INSERT_FIELD, projectId, templateId, field.fieldKey(),
                    field.sortOrder(), field.titleOverride(), operatorId);
        }
    }

    @Override
    public Optional<ReportTemplate> findDefault(long projectId) {
        // 项目级默认优先（project_id 降序：项目号 > 0 排在 0 前面）
        String sql = SELECT + """
                 WHERE project_id IN (?, 0) AND is_default = 1
                 ORDER BY project_id DESC, id ASC
                 LIMIT 1
                """;
        TenantGuard.requireProjectScope(sql, "report_template");
        return jdbc.query(sql, MAPPER, projectId).stream().findFirst().map(this::withFields);
    }

    /**
     * v5.14.0 / W64-11：四级回退。
     * ① 本项目默认且 kernel 匹配 → ② 平台默认且 kernel 匹配 → ③ 平台默认 any → ④ 任意平台默认。
     *
     * <p>run 的内核从 {@code run.kernel} 直接读（V11_17 已有该列），不在报告侧另猜；
     * 查不到该 run 时按 robot 处理，与迁移前行为一致。分级顺序与静态
     * {@link #rank} 一一对应，单测锁死，避免 SQL 与文档各说一套。
     */
    @Override
    public Optional<ReportTemplate> findDefaultForRun(long projectId, String runId) {
        String kernel = runKernel(projectId, runId);
        String sql = SELECT + """
                 WHERE project_id IN (?, 0) AND is_default = 1
                 ORDER BY CASE
                            WHEN project_id = ? AND kernel = ? THEN 0
                            WHEN project_id = 0 AND kernel = ? THEN 1
                            WHEN project_id = 0 AND kernel = 'any' THEN 2
                            ELSE 3
                          END, id ASC
                 LIMIT 1
                """;
        TenantGuard.requireProjectScope(sql, "report_template");
        return jdbc.query(sql, MAPPER, projectId, projectId, kernel, kernel)
                .stream().findFirst().map(this::withFields);
    }

    @Override
    public String runKernel(long projectId, String runId) {
        String sql = "SELECT kernel FROM run WHERE project_id = ? AND run_id = ? LIMIT 1";
        TenantGuard.requireProjectScope(sql, "run");
        List<String> found = jdbc.query(sql, (rs, n) -> rs.getString(1), projectId, runId);
        return found.isEmpty() || found.get(0) == null || found.get(0).isBlank()
                ? ReportTemplate.KERNEL_ROBOT
                : found.get(0).trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 分级排序的唯一真值在 {@link TemplateSelection}；这里保留 public 入口供单测锁死
     * 「SQL CASE 与单一真值一致」，避免基础设施层另写一套排序。
     */
    public static int rank(long templateProjectId, String templateKernel, long projectId,
                           String runKernel) {
        return TemplateSelection.rank(templateProjectId, templateKernel, projectId, runKernel);
    }

    @Override
    public void markDefault(long projectId, long id) {
        // 只清**本项目**的默认标记，绝不能连平台级（project_id = 0）一起清：
        // 平台级 DEFAULT 是所有项目的全局回落值，A 项目把自己的模板设为默认时顺手把
        // 平台默认清掉，B 项目下次冻结就变成「没有模板」了——这是跨项目的隐形副作用
        // （TPLX-02 真实 HTTP 验收 TPLX2-21 抓到的）。
        String clear = "UPDATE report_template SET is_default = 0 WHERE project_id = ?";
        TenantGuard.requireProjectScope(clear, "report_template");
        jdbc.update(clear, projectId);
        String set = "UPDATE report_template SET is_default = 1 WHERE project_id = ? AND id = ?";
        TenantGuard.requireProjectScope(set, "report_template");
        jdbc.update(set, projectId, id);
    }

    private ReportTemplate withFields(ReportTemplate template) {
        TenantGuard.requireProjectScope(SELECT_FIELDS, "report_template_field");
        List<ReportTemplate.Field> fields = jdbc.query(SELECT_FIELDS,
                (rs, rowNum) -> new ReportTemplate.Field(
                        rs.getString("field_key"),
                        rs.getInt("sort_order"),
                        rs.getString("title_override")),
                template.projectId(), template.id());
        return new ReportTemplate(template.id(), template.projectId(), template.name(),
                template.description(), template.version(), template.isDefault(), fields,
                template.createdBy(), template.createdAt(), template.kernel());
    }
}
