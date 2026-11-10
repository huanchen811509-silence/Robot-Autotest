package com.qa.autotest.application.iam;

import com.qa.autotest.application.asset.ProjectAssetService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模板中心（v4.8.0 / FR-v4.8-01..09、v4.8.1 / FR-v4.8-11..13）。
 *
 * <p>模板内容以「引用」为默认：{@code project_template_item.ref_id} 指向用例/用例集/场景，
 * 不复制正文；只有创建项目时用户显式选择「复制」才落副本。内置模板只读（可停用不可删）。
 */
@Service
public class ProjectTemplateService {

    private static final Pattern CODE = Pattern.compile("^([A-Z][A-Z0-9]{1,7})-(RF|PYT)-(\\d{6})-(\\d{3,})$");
    private static final List<String> VISIBILITIES = List.of("private", "team", "public");

    public record TemplateRow(long id, String name, String code, String kernel, String visibility,
                              boolean builtin, String status, Long ownerId, String description,
                              int itemCount, String createdAt) {
    }

    public record TemplateItem(String itemType, Long refId, String mode, String name) {
    }

    public record TemplateDetail(TemplateRow template, List<TemplateItem> items) {
    }

    public record TemplateRequest(String name, String code, String kernel, String visibility,
                                  String description) {
    }

    private final JdbcTemplate jdbc;
    private final AccessControlService access;
    private final AuditService audit;
    private final ProjectAssetService projectAssets;
    private final ProjectAssetRepository assetRepository;

    public ProjectTemplateService(JdbcTemplate jdbc, AccessControlService access, AuditService audit,
                                  ProjectAssetService projectAssets,
                                  ProjectAssetRepository assetRepository) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.projectAssets = projectAssets;
        this.assetRepository = assetRepository;
    }

    // ---------------------------------------------------------------- 列表

    public List<TemplateRow> list(String keyword, String visibility) {
        access.requireGlobalEnter();
        long userId = TenantContext.requireUserId();
        boolean admin = access.isAdmin(userId);
        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.name, t.code, t.kernel, t.visibility, t.builtin, t.status, t.owner_id,
                       t.description, t.created_at,
                       (SELECT COUNT(*) FROM project_template_item i WHERE i.template_id = t.id)
                       + (SELECT COUNT(*) FROM project_template_asset a WHERE a.template_id = t.id)
                       AS item_count
                  FROM project_template t
                 WHERE t.archived = 0
                """);
        List<Object> args = new ArrayList<>();
        if (!admin) {
            sql.append(" AND (t.visibility IN ('team', 'public') OR t.owner_id = ?)");
            args.add(userId);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (t.name LIKE ? OR t.code LIKE ?)");
            args.add("%" + keyword.trim() + "%");
            args.add("%" + keyword.trim() + "%");
        }
        if (visibility != null && !visibility.isBlank()) {
            sql.append(" AND t.visibility = ?");
            args.add(visibility.trim());
        }
        sql.append(" ORDER BY t.builtin DESC, t.id DESC");
        return jdbc.query(sql.toString(), (rs, i) -> new TemplateRow(rs.getLong("id"),
                rs.getString("name"), rs.getString("code"), rs.getString("kernel"),
                rs.getString("visibility"), rs.getInt("builtin") == 1, rs.getString("status"),
                rs.getObject("owner_id") == null ? null : rs.getLong("owner_id"),
                rs.getString("description"), rs.getInt("item_count"),
                String.valueOf(rs.getTimestamp("created_at"))), args.toArray());
    }

    public TemplateDetail get(long id) {
        access.requireGlobalEnter();
        TemplateRow row = one(id);
        List<TemplateItem> items = new ArrayList<>();
        jdbc.query("""
                SELECT i.item_type, i.ref_id, i.mode,
                       COALESCE(
                         (SELECT c.title FROM test_case c WHERE i.item_type = 'CASE' AND c.id = i.ref_id),
                         (SELECT s.name FROM test_set s WHERE i.item_type = 'CASE_SET' AND s.id = i.ref_id),
                         (SELECT sc.name FROM test_scenario sc WHERE i.item_type = 'SCENARIO' AND sc.id = i.ref_id),
                         i.snapshot_json
                       ) AS display_name
                  FROM project_template_item i WHERE i.template_id = ? ORDER BY i.sort_no, i.id
                """, rs -> {
            items.add(new TemplateItem(rs.getString("item_type"),
                    rs.getObject("ref_id") == null ? null : rs.getLong("ref_id"),
                    rs.getString("mode"), rs.getString("display_name")));
        }, id);
        jdbc.query("""
                SELECT a.asset_type, a.asset_id,
                       COALESCE((SELECT d.name FROM device d WHERE a.asset_type = 'DEVICE' AND d.id = a.asset_id),
                                (SELECT c.name FROM credential c WHERE a.asset_type = 'CREDENTIAL' AND c.id = a.asset_id))
                       AS display_name
                  FROM project_template_asset a WHERE a.template_id = ?
                """, rs -> {
            items.add(new TemplateItem(rs.getString("asset_type"),
                    rs.getLong("asset_id"), "reference", rs.getString("display_name")));
        }, id);
        return new TemplateDetail(row, items);
    }

    // ---------------------------------------------------------------- 增改删

    @Transactional
    public TemplateRow create(TemplateRequest request) {
        access.requireGlobalEnter();
        validate(request, null);
        long operator = TenantContext.requireUserId();
        String code = notBlank(request.code()) ? request.code().trim() : generateCode(request);
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO project_template (name, code, kernel, visibility, builtin, status,
                            owner_id, description, config_json, archived, created_by, updated_by)
                        VALUES (?, ?, ?, ?, 0, 'active', ?, ?, '{}', 0, ?, ?)
                        """, new String[]{"id"});
                ps.setString(1, request.name().trim());
                ps.setString(2, code);
                ps.setString(3, normalizeKernel(request.kernel()));
                ps.setString(4, normalizeVisibility(request.visibility()));
                ps.setLong(5, operator);
                ps.setString(6, request.description());
                ps.setLong(7, operator);
                ps.setLong(8, operator);
                return ps;
            }, keys);
            long id = keys.getKey() == null ? 0 : keys.getKey().longValue();
            audit.record("template.create", "project_template", String.valueOf(id), code);
            return one(id);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板编号已存在：" + code);
        }
    }

    @Transactional
    public TemplateRow update(long id, TemplateRequest request, String status) {
        access.requireGlobalEnter();
        TemplateRow current = one(id);
        if (current.builtin()) {
            // 内置模板只允许启停，不允许改编号/名称/共享范围。
            if (notBlank(status)) {
                updateStatus(id, status);
            }
            return one(id);
        }
        if (!access.isAdmin() && current.ownerId() != null
                && current.ownerId() != TenantContext.requireUserId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能修改自己创建的模板");
        }
        if (notBlank(request.visibility()) && "public".equals(request.visibility())
                && !access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可设为全局共享");
        }
        validate(request, id);
        jdbc.update("""
                UPDATE project_template SET name = ?, kernel = ?, visibility = ?, description = ?,
                       updated_by = ?
                 WHERE id = ?
                """, request.name() == null ? current.name() : request.name().trim(),
                request.kernel() == null ? current.kernel() : normalizeKernel(request.kernel()),
                request.visibility() == null ? current.visibility()
                        : normalizeVisibility(request.visibility()),
                request.description() == null ? current.description() : request.description(),
                TenantContext.requireUserId(), id);
        audit.record("template.update", "project_template", String.valueOf(id), "");
        return one(id);
    }

    @Transactional
    public void delete(long id) {
        access.requireGlobalEnter();
        TemplateRow current = one(id);
        if (current.builtin()) {
            throw new BusinessException(ErrorCode.CONFLICT, "内置模板不可删除，只能停用");
        }
        if (!access.isAdmin() && current.ownerId() != null
                && current.ownerId() != TenantContext.requireUserId()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只能删除自己创建的模板");
        }
        jdbc.update("UPDATE project_template SET archived = 1 WHERE id = ?", id);
        audit.record("template.delete", "project_template", String.valueOf(id), current.code());
    }

    @Transactional
    public TemplateRow copy(long id, String code, String name) {
        access.requireGlobalEnter();
        TemplateRow source = one(id);
        String targetCode = notBlank(code) ? code.trim() : generateCode(new TemplateRequest(
                source.name(), null, source.kernel(), source.visibility(), source.description()));
        TemplateRow created = create(new TemplateRequest(notBlank(name) ? name : source.name() + " 副本",
                targetCode, source.kernel(), "private", source.description()));
        jdbc.update("""
                INSERT INTO project_template_item (template_id, item_type, ref_id, mode, sort_no, snapshot_json)
                SELECT ?, item_type, ref_id, mode, sort_no, snapshot_json
                  FROM project_template_item WHERE template_id = ?
                """, created.id(), id);
        jdbc.update("""
                INSERT INTO project_template_asset (template_id, asset_type, asset_id, mode)
                SELECT ?, asset_type, asset_id, mode FROM project_template_asset WHERE template_id = ?
                """, created.id(), id);
        audit.record("template.copy", "project_template", String.valueOf(created.id()),
                "from=" + id);
        return one(created.id());
    }

    // ------------------------------------------------------------ 存为模板

    @Transactional
    public TemplateRow saveAsTemplate(long projectId, String name, String code, String visibility) {
        access.bindProject(projectId);
        access.require(com.qa.autotest.domain.iam.PermissionResource.CASE,
                com.qa.autotest.domain.iam.PermissionAction.UPDATE);
        TemplateRow created = create(new TemplateRequest(name, code, kernelOfProject(projectId),
                notBlank(visibility) ? visibility : "team", "由项目 " + projectId + " 沉淀"));
        jdbc.update("""
                INSERT INTO project_template_item (template_id, item_type, ref_id, mode, sort_no, snapshot_json)
                SELECT ?, 'CASE', tsi.test_case_id, 'reference', tsi.seq_no, tc.title
                  FROM test_set_item tsi
                  JOIN test_set ts ON ts.id = tsi.test_set_id
                  JOIN test_case tc ON tc.id = tsi.test_case_id
                 WHERE ts.project_id = ?
                """, created.id(), projectId);
        jdbc.update("""
                INSERT INTO project_template_asset (template_id, asset_type, asset_id, mode)
                SELECT ?, pa.asset_type, pa.asset_id, 'reference'
                  FROM project_asset pa WHERE pa.project_id = ?
                """, created.id(), projectId);
        audit.record("template.save_as", "project_template", String.valueOf(created.id()),
                "project=" + projectId);
        return one(created.id());
    }

    // ------------------------------------------------------------ 向导选内容

    public record ContentRequest(List<Long> caseIds, List<AssetRef> assets, String setName) {
    }

    public record AssetRef(String assetType, long assetId) {
    }

    public record ContentResult(long testSetId, int cases, int assets) {
    }

    /** 向导「内容」步：把选中的公共用例复制进项目用例集，资产按引用登记。 */
    @Transactional
    public ContentResult applyContent(long projectId, ContentRequest request) {
        access.bindProject(projectId);
        access.require(com.qa.autotest.domain.iam.PermissionResource.CASE,
                com.qa.autotest.domain.iam.PermissionAction.UPDATE);
        long operator = TenantContext.requireUserId();
        List<Long> caseIds = request.caseIds() == null ? List.of() : request.caseIds();
        long setId = 0;
        int copied = 0;
        if (!caseIds.isEmpty()) {
            String setCode = "CS-" + System.currentTimeMillis();
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO test_set (project_id, set_code, name, description, created_by, updated_by)
                        VALUES (?, ?, ?, '向导选入的公共用例', ?, ?)
                        """, new String[]{"id"});
                ps.setLong(1, projectId);
                ps.setString(2, setCode);
                ps.setString(3, notBlank(request.setName()) ? request.setName() : "公共用例选入");
                ps.setLong(4, operator);
                ps.setLong(5, operator);
                return ps;
            }, keys);
            setId = keys.getKey() == null ? 0 : keys.getKey().longValue();
            int seq = 1;
            for (Long caseId : caseIds) {
                Long newId = copyCase(projectId, caseId, operator);
                if (newId == null) {
                    continue;
                }
                jdbc.update("""
                        INSERT INTO test_set_item (test_set_id, test_case_id, seq_no, item_source,
                                                   created_by, updated_by)
                        VALUES (?, ?, ?, 'import', ?, ?)
                        """, setId, newId, seq++, operator, operator);
                copied++;
            }
        }
        int assets = 0;
        if (request.assets() != null) {
            for (AssetRef ref : request.assets()) {
                projectAssets.reference(projectId, ref.assetType(), ref.assetId());
                assets++;
            }
        }
        audit.record("project.content.apply", "project", String.valueOf(projectId),
                "cases=" + copied + " assets=" + assets);
        return new ContentResult(setId, copied, assets);
    }

    private Long copyCase(long projectId, long sourceCaseId, long operator) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM test_case WHERE id = ?", sourceCaseId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> source = rows.get(0);
        String code = String.valueOf(source.get("case_code"));
        List<Long> exists = jdbc.queryForList(
                "SELECT id FROM test_case WHERE project_id = ? AND case_code = ?",
                Long.class, projectId, code);
        if (!exists.isEmpty()) {
            return exists.get(0);
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO test_case (project_id, case_code, external_id, title, case_kind,
                        source_type, priority, domain_tag, status, description, precondition, expected,
                        review_status, source, capability, category, category_l1, category_l2,
                        source_priority, case_class, test_category, ed202_coverage, content_sha,
                        lifecycle_status, version_no, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft', 'import', ?, ?, ?, ?, ?, ?, ?, ?, ?, 'active', 1, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, projectId);
            ps.setString(2, code);
            ps.setString(3, str(source.get("external_id")));
            ps.setString(4, str(source.get("title")));
            ps.setString(5, str(source.get("case_kind")));
            ps.setString(6, str(source.get("source_type")));
            ps.setString(7, str(source.get("priority")));
            ps.setString(8, str(source.get("domain_tag")));
            ps.setObject(9, source.get("status"));
            ps.setString(10, str(source.get("description")));
            ps.setString(11, str(source.get("precondition")));
            ps.setString(12, str(source.get("expected")));
            ps.setString(13, str(source.get("capability")));
            ps.setString(14, str(source.get("category")));
            ps.setString(15, str(source.get("category_l1")));
            ps.setString(16, str(source.get("category_l2")));
            ps.setString(17, str(source.get("source_priority")));
            ps.setString(18, str(source.get("case_class")));
            ps.setString(19, str(source.get("test_category")));
            ps.setString(20, str(source.get("ed202_coverage")));
            ps.setString(21, str(source.get("content_sha")));
            ps.setLong(22, operator);
            ps.setLong(23, operator);
            return ps;
        }, keys);
        long newId = keys.getKey() == null ? 0 : keys.getKey().longValue();
        jdbc.update("""
                INSERT INTO test_case_step (test_case_id, step_no, action, expectation, source_row,
                                            created_by, updated_by)
                SELECT ?, step_no, action, expectation, source_row, ?, ?
                  FROM test_case_step WHERE test_case_id = ?
                """, newId, operator, operator, sourceCaseId);
        return newId;
    }

    // ---------------------------------------------------------------- 内部

    private TemplateRow one(long id) {
        List<TemplateRow> rows = jdbc.query("""
                SELECT t.id, t.name, t.code, t.kernel, t.visibility, t.builtin, t.status, t.owner_id,
                       t.description, t.created_at,
                       (SELECT COUNT(*) FROM project_template_item i WHERE i.template_id = t.id)
                       + (SELECT COUNT(*) FROM project_template_asset a WHERE a.template_id = t.id)
                       AS item_count
                  FROM project_template t WHERE t.id = ? AND t.archived = 0
                """, (rs, i) -> new TemplateRow(rs.getLong("id"), rs.getString("name"),
                rs.getString("code"), rs.getString("kernel"), rs.getString("visibility"),
                rs.getInt("builtin") == 1, rs.getString("status"),
                rs.getObject("owner_id") == null ? null : rs.getLong("owner_id"),
                rs.getString("description"), rs.getInt("item_count"),
                String.valueOf(rs.getTimestamp("created_at"))), id);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "模板不存在");
        }
        return rows.get(0);
    }

    private void updateStatus(long id, String status) {
        String normalized = "disabled".equals(status) ? "disabled" : "active";
        jdbc.update("UPDATE project_template SET status = ?, updated_by = ? WHERE id = ?",
                normalized, TenantContext.requireUserId(), id);
        audit.record("template.status", "project_template", String.valueOf(id), normalized);
    }

    private void validate(TemplateRequest request, Long id) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "模板名称不能为空");
        }
        if (request.code() != null && !request.code().isBlank()
                && !CODE.matcher(request.code().trim()).matches()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "模板编号格式应为 前缀-引擎-年月-序号，例如 API-RF-202611-001");
        }
    }

    private String generateCode(TemplateRequest request) {
        String kernel = "pytest".equalsIgnoreCase(request.kernel()) ? "PYT" : "RF";
        String prefix = "TPL";
        String month = new java.text.SimpleDateFormat("yyyyMM").format(new java.util.Date());
        String like = prefix + "-" + kernel + "-" + month + "-%";
        List<String> codes = jdbc.queryForList(
                "SELECT code FROM project_template WHERE code LIKE ?", String.class, like);
        int max = 0;
        for (String code : codes) {
            String[] parts = code.split("-");
            try {
                max = Math.max(max, Integer.parseInt(parts[parts.length - 1]));
            } catch (NumberFormatException ignored) {
                // 非法历史编号不参与取号
            }
        }
        return String.format("%s-%s-%s-%03d", prefix, kernel, month, max + 1);
    }

    private String kernelOfProject(long projectId) {
        List<String> kernels = jdbc.queryForList(
                "SELECT test_kernel FROM project WHERE id = ?", String.class, projectId);
        return kernels.isEmpty() || kernels.get(0) == null ? "robot" : kernels.get(0);
    }

    private static String normalizeKernel(String kernel) {
        return "pytest".equalsIgnoreCase(kernel) ? "pytest" : "robot";
    }

    private static String normalizeVisibility(String visibility) {
        String value = visibility == null ? "team" : visibility.trim().toLowerCase(Locale.ROOT);
        if ("project".equals(value)) {
            value = "team";
        }
        if (!VISIBILITIES.contains(value)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "共享范围只支持 private / team / public");
        }
        return value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 供前端展示：模板项按类型聚合数量。 */
    public static Map<String, Integer> summarize(List<TemplateItem> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TemplateItem item : items) {
            counts.merge(item.itemType(), 1, Integer::sum);
        }
        return counts;
    }
}
