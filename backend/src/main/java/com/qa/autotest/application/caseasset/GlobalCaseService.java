package com.qa.autotest.application.caseasset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.iam.PlatformProjectService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 公共用例库（v4.6.0 / FR-v4.6-01..13、v4.6.1 / FR-v4.6-20..24）。
 *
 * <p>公共用例存放在单例平台项目（{@code project.kind='platform'}）作用域，复用 {@code test_case}。
 * 导入采用「预览 → 提交」两段式：预览只读并落批次台账，提交才写用例，三层幂等
 * （文件 sha256 / 行 external_id / 版本 content_sha），重复提交不翻倍。
 */
@Service
public class GlobalCaseService {

    /** 导出列序冻结（v4.6.1 / FR-v4.6-20）：改序等于破坏 round-trip 基线。 */
    public static final List<String> EXPORT_COLUMNS = List.of(
            "用例编号", "标题", "一级分类", "二级分类", "优先级", "前置条件", "测试步骤",
            "预期结果", "用例分类", "测试归类", "ED202覆盖情况", "安全级", "生命周期状态", "来源工作表");

    public record GlobalCaseRow(long id, String caseCode, String externalId, String title,
                                String categoryL1, String categoryL2, String priority,
                                String safetyLevel, String lifecycleStatus, int versionNo,
                                String sourceSheet, String contentSha) {
    }

    public record CategoryRow(long id, int level, Long parentId, String name, int sortNo,
                              String status, long caseCount) {
    }

    /** 预览行动作：NEW / UPDATE / UNCHANGED / DEPRECATE。 */
    public record PreviewRow(int rowNo, String caseCode, String title, String categoryL1,
                             String categoryL2, String priority, String action, String message,
                             String contentSha, String sheet, int sourceRow) {
    }

    public record PreviewResult(long batchId, String sha256, int total, int newCount, int updateCount,
                                int unchangedCount, int deprecateCount, int errorCount, boolean replayed,
                                List<PreviewRow> rows, List<CaseImportService.RowError> errors,
                                List<String> skippedSheets) {
    }

    public record CommitResult(long batchId, int inserted, int updated, int unchanged, int deprecated,
                               boolean replayed) {
    }

    /**
     * 统一用例详情契约（OPT-02 / FR-49-07）：公共库 /api/global/cases/{id} 与项目用例详情
     * 共用同一字段集。四列补录 + 来源徽章（external_id / syncJobId / localOverride / sourceBadge）。
     *
     * <p>脱敏硬规则：响应体不得含 secret / 明文口令；凭据类字段只允许 {@code keyMasked} 前缀，
     * 且只放掩码（此处恒为 "****"），绝不序列化任何凭据原文。
     */
    public record CaseDetailView(
            long id, String caseCode, String externalId, String title,
            String categoryL1, String categoryL2, String priority, String safetyLevel,
            String lifecycleStatus, int versionNo, String sourceSheet, String contentSha,
            String aSideCommand, String bSideCommand, String switchConfig,
            String testResult, String testResource,
            Long syncJobId, boolean localOverride, String sourceBadge,
            String keyMaskedCredential) {
    }

    /** OPT-07 / FR-49-28：全选一级 / 全选二级 / 跨表交集三种筛选共用条件。 */
    public record CategoryFilter(String categoryL1, String categoryL2, Long intersectWithSetId) {
    }

    /** FR-49-28：删除结果携带引用数，供 4xx 响应体显式回传。 */
    public record CategoryDeleteResult(boolean deleted, long referenceCount) {
    }

    /** FR-49-30：按分类筛选导出的 CSV。 */
    public record ExportCsv(String fileName, byte[] content, int rowCount) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PlatformProjectService platform;
    private final AccessControlService access;
    private final AuditService audit;

    public GlobalCaseService(JdbcTemplate jdbc, ObjectMapper mapper,
                             PlatformProjectService platform, AccessControlService access,
                             AuditService audit) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.platform = platform;
        this.access = access;
        this.audit = audit;
    }

    /** 公共库写操作（导入/编辑/导出）仅 ADMIN；PM 只读。 */
    private void requireGlobalWrite() {
        access.requireGlobalEnter();
        if (!access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "公共库仅管理员可修改");
        }
    }

    // ------------------------------------------------------------------ 列表

    public PageResult<GlobalCaseRow> page(String keyword, String categoryL1, String categoryL2,
                                          String lifecycleStatus, int page, int size) {
        access.requireGlobalEnter();
        long projectId = platform.id();
        StringBuilder where = new StringBuilder(" WHERE project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (notBlank(keyword)) {
            where.append(" AND (case_code LIKE ? OR title LIKE ? OR external_id LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (notBlank(categoryL1)) {
            where.append(" AND category_l1 = ?");
            args.add(categoryL1.trim());
        }
        if (notBlank(categoryL2)) {
            where.append(" AND category_l2 = ?");
            args.add(categoryL2.trim());
        }
        if (notBlank(lifecycleStatus)) {
            where.append(" AND lifecycle_status = ?");
            args.add(lifecycleStatus.trim());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM test_case" + where,
                Long.class, args.toArray());
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 1);
        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(safeSize);
        listArgs.add((safePage - 1) * safeSize);
        List<GlobalCaseRow> rows = jdbc.query(
                "SELECT id, case_code, external_id, title, category_l1, category_l2, priority,"
                        + " safety_level, lifecycle_status, version_no, source_sheet, content_sha"
                        + " FROM test_case" + where + " ORDER BY category_l1, case_code LIMIT ? OFFSET ?",
                (rs, i) -> new GlobalCaseRow(rs.getLong("id"), rs.getString("case_code"),
                        rs.getString("external_id"), rs.getString("title"),
                        rs.getString("category_l1"), rs.getString("category_l2"),
                        rs.getString("priority"), rs.getString("safety_level"),
                        rs.getString("lifecycle_status"), rs.getInt("version_no"),
                        rs.getString("source_sheet"), rs.getString("content_sha")),
                listArgs.toArray());
        return new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize);
    }

    /** FR-49-07：GET /api/global/cases/{id} 详情（统一契约、脱敏）。 */
    public CaseDetailView detail(long id) {
        access.requireGlobalEnter();
        return findDetail(platform.id(), id);
    }

    /** FR-49-07：项目用例详情，与公共库共用同一字段契约。 */
    public CaseDetailView detailForProject(long projectId, long caseId) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
        return findDetail(projectId, caseId);
    }

    private CaseDetailView findDetail(long projectId, long caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, case_code, external_id, title, category_l1, category_l2, priority,
                       safety_level, lifecycle_status, version_no, source_sheet, content_sha,
                       a_side_command, b_side_command, switch_config, test_result, test_resource,
                       source
                  FROM test_case WHERE id = ? AND project_id = ?
                """, caseId, projectId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用例不存在");
        }
        Map<String, Object> r = rows.get(0);
        Long syncJobId = jdbc.queryForObject("""
                SELECT MAX(sji.job_id) FROM sync_job_item sji
                 WHERE sji.local_id = ? OR sji.remote_id = ?
                """, Long.class, caseId, caseId);
        boolean localOverride = "local_override".equals(text(r.get("source")));
        String badge = localOverride ? "LOCAL_OVERRIDE"
                : syncJobId != null ? "GLOBAL_SYNC" : "LOCAL";
        // 凭据类字段只允许 keyMasked 前缀，且只放掩码；绝不把任何凭据原文放进响应。
        return new CaseDetailView(
                ((Number) r.get("id")).longValue(), text(r.get("case_code")),
                text(r.get("external_id")), text(r.get("title")),
                text(r.get("category_l1")), text(r.get("category_l2")),
                text(r.get("priority")), text(r.get("safety_level")),
                text(r.get("lifecycle_status")), ((Number) r.get("version_no")).intValue(),
                text(r.get("source_sheet")), text(r.get("content_sha")),
                text(r.get("a_side_command")), text(r.get("b_side_command")),
                text(r.get("switch_config")), text(r.get("test_result")),
                text(r.get("test_resource")),
                syncJobId, localOverride, badge, "****");
    }

    // ---------------------------------------------------------------- 分类

    public List<CategoryRow> categories() {
        access.requireGlobalEnter();
        long projectId = platform.id();
        return jdbc.query("""
                SELECT c.id, c.level, c.parent_id, c.name, c.sort_no, c.status,
                       (SELECT COUNT(*) FROM test_case t
                         WHERE t.project_id = c.project_id AND t.lifecycle_status = 'active'
                           AND (t.category_l1 = c.name OR t.category_l2 = c.name)) AS case_count
                  FROM case_category c
                 WHERE c.project_id = ?
                 ORDER BY c.level, c.sort_no, c.name
                """, (rs, i) -> new CategoryRow(rs.getLong("id"), rs.getInt("level"),
                rs.getObject("parent_id") == null ? null : rs.getLong("parent_id"),
                rs.getString("name"), rs.getInt("sort_no"), rs.getString("status"),
                rs.getLong("case_count")), projectId);
    }

    public CategoryRow createCategory(int level, Long parentId, String name, int sortNo) {
        requireGlobalWrite();
        if (level != 1 && level != 2) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "分类层级只能是 1 或 2");
        }
        if (!notBlank(name)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "分类名称不能为空");
        }
        if (level == 2 && parentId == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "二级分类必须指定一级分类");
        }
        long projectId = platform.id();
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO case_category (project_id, level, parent_id, name, sort_no, created_by)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, new String[]{"id"});
                ps.setLong(1, projectId);
                ps.setInt(2, level);
                if (parentId == null) {
                    ps.setNull(3, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(3, parentId);
                }
                ps.setString(4, name.trim());
                ps.setInt(5, sortNo);
                ps.setObject(6, TenantContext.userId());
                return ps;
            }, keys);
            long id = keys.getKey() == null ? 0 : keys.getKey().longValue();
            return new CategoryRow(id, level, parentId, name.trim(), sortNo, "active", 0);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "分类已存在：" + name);
        }
    }

    public void deleteCategory(long id) {
        requireGlobalWrite();
        long projectId = platform.id();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name FROM case_category WHERE id = ? AND project_id = ?", id, projectId);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分类不存在");
        }
        String name = String.valueOf(rows.get(0).get("name"));
        Long used = jdbc.queryForObject("""
                SELECT COUNT(*) FROM test_case
                 WHERE project_id = ? AND (category_l1 = ? OR category_l2 = ?)
                """, Long.class, projectId, name, name);
        if (used != null && used > 0) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "分类下仍有 " + used + " 条用例，不能删除");
        }
        jdbc.update("DELETE FROM case_category WHERE id = ? AND project_id = ?", id, projectId);
        audit.record("global.case.category.delete", "case_category", String.valueOf(id), name);
    }

    private void requireScopeRead(Long projectId) {
        if (projectId == null) {
            access.requireGlobalEnter();
            return;
        }
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.READ);
    }

    private void requireScopeWrite(Long projectId) {
        if (projectId == null) {
            requireGlobalWrite();
            return;
        }
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
    }

    /** FR-49-26：项目域分类列表（scope=project 按真实 project_id 隔离）。 */
    public List<CategoryRow> categoriesInScope(Long projectId) {
        requireScopeRead(projectId);
        long scopeProject = projectId == null ? platform.id() : projectId;
        return jdbc.query("""
                SELECT c.id, c.level, c.parent_id, c.name, c.sort_no, c.status,
                       (SELECT COUNT(*) FROM test_case t
                         WHERE t.project_id = c.project_id AND t.lifecycle_status = 'active'
                           AND (t.category_l1_id = c.id OR t.category_l2_id = c.id
                                OR (t.category_l1_id IS NULL AND t.category_l1 = c.name)
                                OR (t.category_l2_id IS NULL AND t.category_l2 = c.name)))
                       AS case_count
                  FROM case_category c
                 WHERE c.project_id = ?
                 ORDER BY c.level, c.sort_no, c.name
                """, (rs, i) -> new CategoryRow(rs.getLong("id"), rs.getInt("level"),
                rs.getObject("parent_id") == null ? null : rs.getLong("parent_id"),
                rs.getString("name"), rs.getInt("sort_no"), rs.getString("status"),
                rs.getLong("case_count")), scopeProject);
    }

    /** FR-49-26：项目域建分类，显式写 scope=project；平台域沿用默认 scope。 */
    public CategoryRow createCategoryInScope(Long projectId, int level, Long parentId,
                                             String name, int sortNo) {
        requireScopeWrite(projectId);
        if (level != 1 && level != 2) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "分类层级只能是 1 或 2");
        }
        if (!notBlank(name)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "分类名称不能为空");
        }
        if (level == 2 && parentId == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "二级分类必须指定一级分类");
        }
        long scopeProject = projectId == null ? platform.id() : projectId;
        String scope = projectId == null ? "platform" : "project";
        if (level == 2) {
            List<Long> parents = jdbc.queryForList(
                    "SELECT id FROM case_category WHERE id = ? AND project_id = ? AND level = 1",
                    Long.class, parentId, scopeProject);
            if (parents.isEmpty()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "非法父子组合：父分类不是该作用域下的一级分类");
            }
        }
        try {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO case_category (project_id, scope, level, parent_id, name,
                                                  sort_no, created_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, new String[]{"id"});
                ps.setLong(1, scopeProject);
                ps.setString(2, scope);
                ps.setInt(3, level);
                if (parentId == null) {
                    ps.setNull(4, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(4, parentId);
                }
                ps.setString(5, name.trim());
                ps.setInt(6, sortNo);
                ps.setObject(7, TenantContext.userId());
                return ps;
            }, keys);
            long id = keys.getKey() == null ? 0 : keys.getKey().longValue();
            return new CategoryRow(id, level, parentId, name.trim(), sortNo, "active", 0);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "分类已存在：" + name);
        }
    }

    /** FR-49-28：删除有引用分类拒绝，返回引用数供 4xx 响应显式带出。 */
    public CategoryDeleteResult deleteCategoryInScope(Long projectId, long id) {
        requireScopeWrite(projectId);
        long scopeProject = projectId == null ? platform.id() : projectId;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name FROM case_category WHERE id = ? AND project_id = ?", id, scopeProject);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "分类不存在");
        }
        String name = text(rows.get(0).get("name"));
        Long used = jdbc.queryForObject("""
                SELECT COUNT(*) FROM test_case
                 WHERE project_id = ? AND (category_l1_id = ? OR category_l2_id = ?
                    OR (category_l1_id IS NULL AND category_l1 = ?)
                    OR (category_l2_id IS NULL AND category_l2 = ?))
                """, Long.class, scopeProject, id, id, name, name);
        long count = used == null ? 0 : used;
        if (count > 0) {
            return new CategoryDeleteResult(false, count);
        }
        jdbc.update("DELETE FROM case_category WHERE id = ? AND project_id = ?", id, scopeProject);
        audit.record("global.case.category.delete", "case_category", String.valueOf(id), name);
        return new CategoryDeleteResult(true, 0);
    }

    /** FR-49-28：设置二级分类时二级必须属于所选一级（纯校验，非法组合 → 400）。 */
    public void validateCategoryPair(Long projectId, Long categoryL1Id, Long categoryL2Id) {
        if (categoryL2Id == null) {
            return;
        }
        if (categoryL1Id == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "设置二级分类必须同时指定一级分类");
        }
        long scopeProject = projectId == null ? platform.id() : projectId;
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT parent_id FROM case_category WHERE id = ? AND project_id = ? AND level = 2",
                categoryL2Id, scopeProject);
        if (rows.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "二级分类不存在");
        }
        Object parent = rows.get(0).get("parent_id");
        if (parent == null || ((Number) parent).longValue() != categoryL1Id) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "非法父子组合：二级分类 " + categoryL2Id + " 不属于一级分类 " + categoryL1Id);
        }
    }

    /** FR-49-28：绑定项目用例两级分类 id（先校验父子，再写可空外键列）。 */
    public void bindCaseCategory(long projectId, long caseId, Long categoryL1Id, Long categoryL2Id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CASE, PermissionAction.UPDATE);
        validateCategoryPair(projectId, categoryL1Id, categoryL2Id);
        int changed = jdbc.update("""
                UPDATE test_case SET category_l1_id = ?, category_l2_id = ?, updated_by = ?
                 WHERE id = ? AND project_id = ?
                """, categoryL1Id, categoryL2Id, TenantContext.userId(), caseId, projectId);
        if (changed == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用例不存在");
        }
    }

    // ------------------------------------------------------------ 导入预览

    @Transactional
    /** 兼容旧调用方：等价于 {@code force=false}。 */
    public PreviewResult preview(InputStream input, String sourceName) {
        return preview(input, sourceName, false);
    }

    /**
     * 两段式导入第一步：解析工作簿并算出增量差异。
     *
     * @param force v4.11.0 / FR-v4.11-18：{@code false} 时，同一份文件命中已提交批次直接回放
     *              （{@code replayed=true}，幂等语义不变）；{@code true} 时按当前库重算，
     *              且<b>不写 import_item、不改批次状态</b>——历史记录必须保持原样。
     */
    public PreviewResult preview(InputStream input, String sourceName, boolean force) {
        requireGlobalWrite();
        long projectId = platform.id();
        CaseImportService.ParsedWorkbook workbook = CaseImportService.parse(input);
        String sourceFile = notBlank(sourceName) ? sourceName : "upload.xlsx";
        List<CaseImportService.RowError> errors = new ArrayList<>();
        List<PlannedRow> planned = new ArrayList<>();
        List<String> skippedSheets = new ArrayList<>();
        Map<String, ExistingCase> existing = existingByExternal(projectId);
        Set<String> seen = new LinkedHashSet<>();

        for (CaseImportService.ParsedSheet sheet : workbook.sheets()) {
            // 看板/汇总页是明细的派生视图，导入时跳过，避免同一编号在文件内自撞。
            if (sheet.sheetName().contains("看板") || sheet.sheetName().contains("汇总")) {
                skippedSheets.add(sheet.sheetName());
                continue;
            }
            // 环境/说明等工作表不是用例表：既无编号也无标题列时静默跳过，不计入错误行。
            if (!sheet.columnMap().containsKey("caseCode")
                    && !sheet.columnMap().containsKey("title")) {
                continue;
            }
            if (!sheet.columnMap().containsKey("caseCode") || !sheet.columnMap().containsKey("title")) {
                errors.add(new CaseImportService.RowError(sheet.headerRowNum(), "columns",
                        "工作表「" + sheet.sheetName() + "」缺少必需列：用例编号 / 标题"));
                continue;
            }
            for (CaseImportService.ImportRow row : sheet.rows()) {
                String externalId = notBlank(row.caseCode())
                        ? row.caseCode().trim()
                        : "AUTO-" + sha256Hex((sheet.sheetName() + "#" + row.sourceRow()
                        + "#" + row.title()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                        .substring(0, 16);
                if (!notBlank(row.title())) {
                    errors.add(new CaseImportService.RowError(row.rowNum(), "title", "标题为空"));
                    continue;
                }
                if (!seen.add(externalId.toUpperCase(Locale.ROOT))) {
                    errors.add(new CaseImportService.RowError(row.rowNum(), "caseCode",
                            "文件内编号重复：" + externalId));
                    continue;
                }
                String sha = contentSha(row, externalId);
                ExistingCase current = existing.get(externalId.toUpperCase(Locale.ROOT));
                String action;
                String message = "";
                if (current == null) {
                    action = "NEW";
                } else if ("deprecated".equals(current.lifecycleStatus())) {
                    action = "UPDATE";
                    message = "恢复已失效用例";
                } else if (current.contentSha() == null || !current.contentSha().equals(sha)) {
                    action = "UPDATE";
                    message = "内容有变更";
                } else {
                    action = "UNCHANGED";
                }
                planned.add(new PlannedRow(row.rowNum(), externalId, action, message, sha,
                        row.sourceSheet(), row.sourceRow(), row));
            }
        }

        // 反向：库中 active 但文件中不存在 → 建议失效（只能由用户确认提交）。
        for (Map.Entry<String, ExistingCase> entry : existing.entrySet()) {
            if ("active".equals(entry.getValue().lifecycleStatus())
                    && !seen.contains(entry.getKey())) {
                ExistingCase item = entry.getValue();
                CaseImportService.ImportRow placeholder = new CaseImportService.ImportRow(
                        item.sourceRow(), item.caseCode(), item.title(), item.categoryL1(),
                        item.priority(), "", "", "", "", "", item.safetyLevel(), item.categoryL2(),
                        "", "", "", "", item.sourceSheet(), List.of(), item.sourceRow());
                planned.add(new PlannedRow(item.sourceRow(), item.externalId(), "DEPRECATE",
                        "源文件已无此用例", item.contentSha(), item.sourceSheet(), item.sourceRow(),
                        placeholder));
            }
        }

        // FR-v4.6-20 防护：单文件行数上限，避免误传大表把导入拖成小时级。
        if (planned.size() > 20_000) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "单文件用例行数 " + planned.size() + " 超过 20000 上限");
        }

        int newCount = count(planned, "NEW");
        int updateCount = count(planned, "UPDATE");
        int unchanged = count(planned, "UNCHANGED");
        int deprecate = count(planned, "DEPRECATE");
        String fileSha = workbook.sha256();

        // 明细行只在这里构造一次：force 重算分支也要返回它，但**不落库**。
        List<PreviewRow> previewRows = new ArrayList<>();
        for (int i = 0; i < planned.size(); i++) {
            PlannedRow item = planned.get(i);
            previewRows.add(new PreviewRow(i + 1, item.externalId(), item.row().title(),
                    item.row().category(), item.row().categoryL2(), item.row().priority(),
                    item.action(), item.message(), item.contentSha(), item.sheet(), item.sourceRow()));
        }

        Optional<Map<String, Object>> existingBatch = jdbc.queryForList(
                "SELECT * FROM import_batch WHERE project_id = ? AND kind = 'global' AND checksum = ?",
                projectId, fileSha).stream().findFirst();

        long batchId;
        boolean replayed = false;
        if (existingBatch.isPresent() && "COMMITTED".equals(existingBatch.get().get("status"))) {
            batchId = ((Number) existingBatch.get().get("id")).longValue();
            replayed = true;
            if (!force) {
                return replayPreview(batchId, fileSha, skippedSheets);
            }
            // force：重算但不落库。已提交批次的 import_item 与状态一律不动，
            // 否则「导入记录」里的历史会变成 PREFLIGHT + 新计数，等于篡改历史。
            audit.record("global.case.import.preview.force", "import_batch", String.valueOf(batchId),
                    "sha=" + fileSha + " new=" + newCount + " update=" + updateCount
                            + " unchanged=" + unchanged + " deprecate=" + deprecate);
            return new PreviewResult(batchId, fileSha, planned.size(), newCount, updateCount,
                    unchanged, deprecate, errors.size(), false, previewRows, errors, skippedSheets);
        }
        if (existingBatch.isPresent()) {
            batchId = ((Number) existingBatch.get().get("id")).longValue();
            jdbc.update("DELETE FROM import_item WHERE batch_id = ?", batchId);
            jdbc.update("""
                    UPDATE import_batch SET source_name = ?, kind = 'global', total_rows = ?,
                           new_count = ?, updated_count = ?, unchanged_count = ?, deprecated_count = ?,
                           status = 'PREFLIGHT'
                     WHERE id = ?
                    """, sourceFile, planned.size(), newCount, updateCount, unchanged, deprecate, batchId);
        } else {
            KeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                var ps = con.prepareStatement("""
                        INSERT INTO import_batch (project_id, source_type, source_name, checksum,
                                                  version, status, kind, total_rows, new_count,
                                                  updated_count, unchanged_count, deprecated_count)
                        VALUES (?, 'excel', ?, ?, 1, 'PREFLIGHT', 'global', ?, ?, ?, ?, ?)
                        """, new String[]{"id"});
                ps.setLong(1, projectId);
                ps.setString(2, sourceFile);
                ps.setString(3, fileSha);
                ps.setInt(4, planned.size());
                ps.setInt(5, newCount);
                ps.setInt(6, updateCount);
                ps.setInt(7, unchanged);
                ps.setInt(8, deprecate);
                return ps;
            }, keys);
            batchId = keys.getKey() == null ? 0 : keys.getKey().longValue();
        }

        int rowNo = 0;
        for (PlannedRow item : planned) {
            rowNo++;
            String payload;
            try {
                payload = mapper.writeValueAsString(item.row());
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入行序列化失败");
            }
            jdbc.update("""
                    INSERT INTO import_item (batch_id, row_no, case_code, action, message, payload_json)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, batchId, rowNo, item.externalId(), item.action(), item.message(), payload);
        }

        audit.record("global.case.import.preview", "import_batch", String.valueOf(batchId),
                "sha=" + fileSha + " new=" + newCount + " update=" + updateCount
                        + " unchanged=" + unchanged + " deprecate=" + deprecate);
        return new PreviewResult(batchId, fileSha, planned.size(), newCount, updateCount, unchanged,
                deprecate, errors.size(), replayed, previewRows, errors, skippedSheets);
    }

    private PreviewResult replayPreview(long batchId, String fileSha, List<String> skippedSheets) {
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT row_no, case_code, action, message, payload_json FROM import_item"
                        + " WHERE batch_id = ? ORDER BY row_no", batchId);
        List<PreviewRow> rows = new ArrayList<>();
        int newCount = 0;
        int updateCount = 0;
        int unchanged = 0;
        int deprecate = 0;
        for (Map<String, Object> item : items) {
            String action = String.valueOf(item.get("action"));
            switch (action) {
                case "NEW" -> newCount++;
                case "UPDATE" -> updateCount++;
                case "UNCHANGED" -> unchanged++;
                case "DEPRECATE" -> deprecate++;
                default -> {
                }
            }
            CaseImportService.ImportRow row = readRow(String.valueOf(item.get("payload_json")));
            rows.add(new PreviewRow(((Number) item.get("row_no")).intValue(),
                    String.valueOf(item.get("case_code")), row.title(), row.category(),
                    row.categoryL2(), row.priority(), action,
                    item.get("message") == null ? "" : String.valueOf(item.get("message")),
                    contentSha(row, String.valueOf(item.get("case_code"))),
                    row.sourceSheet(), row.sourceRow()));
        }
        return new PreviewResult(batchId, fileSha, rows.size(), newCount, updateCount, unchanged,
                deprecate, 0, true, rows, List.of(), skippedSheets);
    }

    private CaseImportService.ImportRow readRow(String json) {
        try {
            return mapper.readValue(json, CaseImportService.ImportRow.class);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导入行反序列化失败");
        }
    }

    // ------------------------------------------------------------ 导入提交

    @Transactional
    public CommitResult commit(long batchId) {
        requireGlobalWrite();
        long projectId = platform.id();
        Map<String, Object> batch = jdbc.queryForList(
                "SELECT * FROM import_batch WHERE id = ? AND project_id = ?", batchId, projectId)
                .stream().findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "导入批次不存在"));
        if ("COMMITTED".equals(batch.get("status"))) {
            return new CommitResult(batchId, 0, 0, 0, 0, true);
        }
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM import_item WHERE batch_id = ? ORDER BY row_no", batchId);
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "批次没有可提交的行");
        }
        Long operator = TenantContext.userId();
        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        int deprecated = 0;
        for (Map<String, Object> item : items) {
            String action = String.valueOf(item.get("action"));
            CaseImportService.ImportRow row = readRow(String.valueOf(item.get("payload_json")));
            String externalId = String.valueOf(item.get("case_code"));
            switch (action) {
                case "NEW" -> {
                    long caseId = insertCase(projectId, externalId, row, operator);
                    writeVersion(projectId, caseId, 1, contentSha(row, externalId),
                            row, batchId, operator);
                    insertSteps(caseId, row, operator);
                    jdbc.update("UPDATE import_item SET case_id = ? WHERE id = ?",
                            caseId, item.get("id"));
                    inserted++;
                }
                case "UPDATE" -> {
                    ExistingCase current = findExisting(projectId, externalId);
                    if (current == null) {
                        long caseId = insertCase(projectId, externalId, row, operator);
                        writeVersion(projectId, caseId, 1, contentSha(row, externalId),
                                row, batchId, operator);
                        insertSteps(caseId, row, operator);
                        jdbc.update("UPDATE import_item SET case_id = ? WHERE id = ?",
                                caseId, item.get("id"));
                        inserted++;
                    } else {
                        int nextVersion = current.versionNo() + 1;
                        updateCase(projectId, current.id(), externalId, row, nextVersion, operator);
                        replaceSteps(current.id(), row, operator);
                        writeVersion(projectId, current.id(), nextVersion,
                                contentSha(row, externalId), row, batchId, operator);
                        jdbc.update("UPDATE import_item SET case_id = ? WHERE id = ?",
                                current.id(), item.get("id"));
                        updated++;
                    }
                }
                case "DEPRECATE" -> {
                    jdbc.update("""
                            UPDATE test_case SET lifecycle_status = 'deprecated', updated_by = ?
                             WHERE project_id = ? AND external_id = ?
                            """, operator, projectId, externalId);
                    deprecated++;
                }
                default -> unchanged++;
            }
        }
        jdbc.update("""
                UPDATE import_batch SET status = 'COMMITTED', committed_at = NOW(3),
                       new_count = ?, updated_count = ?, unchanged_count = ?, deprecated_count = ?
                 WHERE id = ?
                """, inserted, updated, unchanged, deprecated, batchId);
        int categories = syncCategories(projectId, operator);
        audit.record("global.case.import.commit", "import_batch", String.valueOf(batchId),
                "inserted=" + inserted + " updated=" + updated + " unchanged=" + unchanged
                        + " deprecated=" + deprecated + " categories=" + categories);
        return new CommitResult(batchId, inserted, updated, unchanged, deprecated, false);
    }

    /** 从用例表反推分类字典（FR-v4.6-10）：导入后一级/二级分类可见、可筛选。 */
    private int syncCategories(long projectId, Long operator) {
        List<String> level1 = jdbc.queryForList("""
                SELECT DISTINCT category_l1 FROM test_case
                 WHERE project_id = ? AND category_l1 IS NOT NULL AND category_l1 <> ''
                 ORDER BY category_l1
                """, String.class, projectId);
        Map<String, Long> parentIds = new HashMap<>();
        int created = 0;
        for (String name : level1) {
            parentIds.put(name, ensureCategory(projectId, 1, null, name, operator));
            created++;
        }
        List<Map<String, Object>> level2 = jdbc.queryForList("""
                SELECT DISTINCT category_l1, category_l2 FROM test_case
                 WHERE project_id = ? AND category_l2 IS NOT NULL AND category_l2 <> ''
                """, projectId);
        for (Map<String, Object> row : level2) {
            String parent = row.get("category_l1") == null ? "" : String.valueOf(row.get("category_l1"));
            ensureCategory(projectId, 2, parentIds.get(parent), String.valueOf(row.get("category_l2")),
                    operator);
            created++;
        }
        return created;
    }

    private long ensureCategory(long projectId, int level, Long parentId, String name, Long operator) {
        List<Long> found = jdbc.queryForList("""
                SELECT id FROM case_category
                 WHERE project_id = ? AND level = ? AND name = ? AND IFNULL(parent_id, 0) = IFNULL(?, 0)
                """, Long.class, projectId, level, truncate(name, 128), parentId);
        if (!found.isEmpty()) {
            return found.get(0);
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO case_category (project_id, level, parent_id, name, sort_no, created_by)
                    VALUES (?, ?, ?, ?, 0, ?)
                    """, new String[]{"id"});
            ps.setLong(1, projectId);
            ps.setInt(2, level);
            if (parentId == null) {
                ps.setNull(3, java.sql.Types.BIGINT);
            } else {
                ps.setLong(3, parentId);
            }
            ps.setString(4, truncate(name, 128));
            ps.setObject(5, operator);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    private long insertCase(long projectId, String externalId, CaseImportService.ImportRow row,
                            Long operator) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO test_case (project_id, case_code, external_id, title, case_kind,
                        source_type, priority, safety_level, domain_tag, status, description,
                        precondition, expected, review_status, source, capability,
                        category_l1, category_l2, source_priority, case_class, test_category,
                        ed202_coverage, source_sheet, content_sha, lifecycle_status, version_no,
                        a_side_command, b_side_command, switch_config, test_result, test_resource,
                        created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, 'ledger', ?, ?, ?, 1, ?, ?, ?, 'draft', 'import', ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, 'active', 1, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, projectId);
            ps.setString(2, notBlank(row.caseCode()) ? truncate(row.caseCode().trim(), 64)
                    : truncate(externalId, 64));
            ps.setString(3, externalId);
            ps.setString(4, row.title());
            ps.setString(5, truncate(row.category(), 32));
            ps.setString(6, row.priority());
            ps.setString(7, safetyLevel(row.safetyLevel()));
            ps.setString(8, row.domainTag());
            ps.setString(9, row.description());
            ps.setString(10, row.precondition());
            ps.setString(11, row.expected());
            ps.setString(12, row.capability());
            ps.setString(13, truncate(row.category(), 128));
            ps.setString(14, truncate(row.categoryL2(), 128));
            ps.setString(15, truncate(row.sourcePriority(), 16));
            ps.setString(16, truncate(row.caseClass(), 64));
            ps.setString(17, truncate(row.testCategory(), 64));
            ps.setString(18, row.ed202Coverage());
            ps.setString(19, truncate(row.sourceSheet(), 128));
            ps.setString(20, contentSha(row, externalId));
            ps.setString(21, row.aSideCommand());
            ps.setString(22, row.bSideCommand());
            ps.setString(23, row.switchConfig());
            ps.setString(24, row.testResult());
            ps.setString(25, row.testResource());
            ps.setObject(26, operator);
            ps.setObject(27, operator);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    private void updateCase(long projectId, long caseId, String externalId,
                            CaseImportService.ImportRow row, int nextVersion, Long operator) {
        jdbc.update("""
                UPDATE test_case SET title = ?, case_kind = ?, priority = ?, safety_level = ?,
                       domain_tag = ?, description = ?, precondition = ?, expected = ?,
                       capability = ?, category_l1 = ?, category_l2 = ?,
                       source_priority = ?, case_class = ?, test_category = ?, ed202_coverage = ?,
                       source_sheet = ?, content_sha = ?, lifecycle_status = 'active',
                       a_side_command = ?, b_side_command = ?, switch_config = ?, test_result = ?,
                       test_resource = ?,
                       version_no = ?, external_id = ?, updated_by = ?
                 WHERE project_id = ? AND id = ?
                """, row.title(), truncate(row.category(), 32), row.priority(),
                safetyLevel(row.safetyLevel()), row.domainTag(), row.description(),
                row.precondition(), row.expected(), row.capability(),
                truncate(row.category(), 128), truncate(row.categoryL2(), 128),
                truncate(row.sourcePriority(), 16), truncate(row.caseClass(), 64),
                truncate(row.testCategory(), 64), row.ed202Coverage(),
                truncate(row.sourceSheet(), 128), contentSha(row, externalId),
                row.aSideCommand(), row.bSideCommand(), row.switchConfig(),
                row.testResult(), row.testResource(),
                nextVersion, externalId, operator, projectId, caseId);
    }

    private void insertSteps(long caseId, CaseImportService.ImportRow row, Long operator) {
        List<String> steps = row.steps();
        if (steps == null) {
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            jdbc.update("""
                    INSERT INTO test_case_step (test_case_id, step_no, action, expectation,
                                                source_row, created_by, updated_by)
                    VALUES (?, ?, ?, '', ?, ?, ?)
                    """, caseId, i + 1, steps.get(i), row.sourceRow(), operator, operator);
        }
    }

    private void replaceSteps(long caseId, CaseImportService.ImportRow row, Long operator) {
        jdbc.update("DELETE FROM test_case_step WHERE test_case_id = ?", caseId);
        insertSteps(caseId, row, operator);
    }

    private void writeVersion(long projectId, long caseId, int versionNo, String sha,
                              CaseImportService.ImportRow row, long batchId, Long operator) {
        try {
            jdbc.update("""
                    INSERT INTO case_version (project_id, case_id, version_no, content_sha,
                                              snapshot_json, source, batch_id, created_by)
                    VALUES (?, ?, ?, ?, ?, 'import', ?, ?)
                    """, projectId, caseId, versionNo, sha, mapper.writeValueAsString(row), batchId,
                    operator);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "版本快照序列化失败");
        }
    }

    // ------------------------------------------------------------ 生命周期

    public void deprecate(long caseId) {
        requireGlobalWrite();
        int changed = jdbc.update("""
                UPDATE test_case SET lifecycle_status = 'deprecated', updated_by = ?
                 WHERE project_id = ? AND id = ?
                """, TenantContext.userId(), platform.id(), caseId);
        if (changed == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用例不存在");
        }
        audit.record("global.case.deprecate", "test_case", String.valueOf(caseId), "");
    }

    public void restore(long caseId) {
        requireGlobalWrite();
        int changed = jdbc.update("""
                UPDATE test_case SET lifecycle_status = 'active', updated_by = ?
                 WHERE project_id = ? AND id = ?
                """, TenantContext.userId(), platform.id(), caseId);
        if (changed == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "用例不存在");
        }
        audit.record("global.case.restore", "test_case", String.valueOf(caseId), "");
    }

    // ---------------------------------------------------------------- 导出

    public record ExportFile(String fileName, byte[] content, int rowCount, String sha256) {
    }

    public ExportFile export(String categoryL1, String lifecycleStatus) {
        requireGlobalWrite();
        long projectId = platform.id();
        StringBuilder where = new StringBuilder(" WHERE project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        if (notBlank(categoryL1)) {
            where.append(" AND category_l1 = ?");
            args.add(categoryL1.trim());
        }
        if (notBlank(lifecycleStatus)) {
            where.append(" AND lifecycle_status = ?");
            args.add(lifecycleStatus.trim());
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT case_code, external_id, title, category_l1, category_l2, priority,"
                        + " precondition, expected, case_class, test_category, ed202_coverage,"
                        + " safety_level, lifecycle_status, source_sheet, id"
                        + " FROM test_case" + where + " ORDER BY category_l1, case_code", args.toArray());
        Map<Long, List<String>> steps = stepsFor(rows);
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("公共用例");
            CellStyle headerStyle = workbook.createCellStyle();
            Font font = workbook.createFont();
            font.setBold(true);
            headerStyle.setFont(font);
            Row header = sheet.createRow(0);
            for (int i = 0; i < EXPORT_COLUMNS.size(); i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(EXPORT_COLUMNS.get(i));
                cell.setCellStyle(headerStyle);
            }
            int r = 1;
            for (Map<String, Object> row : rows) {
                Row line = sheet.createRow(r++);
                line.createCell(0).setCellValue(text(row.get("external_id"), row.get("case_code")));
                line.createCell(1).setCellValue(text(row.get("title")));
                line.createCell(2).setCellValue(text(row.get("category_l1")));
                line.createCell(3).setCellValue(text(row.get("category_l2")));
                line.createCell(4).setCellValue(text(row.get("priority")));
                line.createCell(5).setCellValue(text(row.get("precondition")));
                line.createCell(6).setCellValue(String.join("\n", steps.getOrDefault(
                        ((Number) row.get("id")).longValue(), List.of())));
                line.createCell(7).setCellValue(text(row.get("expected")));
                line.createCell(8).setCellValue(text(row.get("case_class")));
                line.createCell(9).setCellValue(text(row.get("test_category")));
                line.createCell(10).setCellValue(text(row.get("ed202_coverage")));
                line.createCell(11).setCellValue(text(row.get("safety_level")));
                line.createCell(12).setCellValue(text(row.get("lifecycle_status")));
                line.createCell(13).setCellValue(text(row.get("source_sheet")));
            }
            for (int i = 0; i < EXPORT_COLUMNS.size(); i++) {
                sheet.setColumnWidth(i, Math.min(16000, Math.max(3000,
                        EXPORT_COLUMNS.get(i).length() * 512)));
            }
            Sheet summary = workbook.createSheet("分类统计（派生）");
            Row head = summary.createRow(0);
            head.createCell(0).setCellValue("一级分类");
            head.createCell(1).setCellValue("用例数");
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                counts.merge(text(row.get("category_l1")), 1, Integer::sum);
            }
            int sr = 1;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                Row line = summary.createRow(sr++);
                line.createCell(0).setCellValue(entry.getKey());
                line.createCell(1).setCellValue(entry.getValue());
            }
            workbook.write(out);
            byte[] content = out.toByteArray();
            String sha = sha256Hex(content);
            String fileName = "ed202-cases-" + sha.substring(0, 12) + ".xlsx";
            jdbc.update("""
                    INSERT INTO export_record (project_id, run_key, export_format, file_name,
                                               sha256, size_bytes, export_status, created_by)
                    VALUES (?, ?, 'xlsx', ?, ?, ?, 'created', ?)
                    """, projectId, "global-cases", fileName, sha, content.length,
                    TenantContext.userId());
            audit.record("global.case.export", "test_case", String.valueOf(projectId),
                    "rows=" + rows.size() + " sha=" + sha.substring(0, 12));
            return new ExportFile(fileName, content, rows.size(), sha);
        } catch (java.io.IOException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导出失败：" + ex.getMessage());
        }
    }

    private Map<Long, List<String>> stepsFor(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ids.add(row.get("id"));
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        Map<Long, List<String>> result = new HashMap<>();
        jdbc.query("SELECT test_case_id, action FROM test_case_step WHERE test_case_id IN ("
                        + placeholders + ") ORDER BY test_case_id, step_no",
                rs -> {
                    result.computeIfAbsent(rs.getLong("test_case_id"), k -> new ArrayList<>())
                            .add(rs.getString("action"));
                }, ids.toArray());
        return result;
    }

    // ---------------------------------------------------------------- 批次

    public PageResult<Map<String, Object>> batches(int page, int size) {
        access.requireGlobalEnter();
        long projectId = platform.id();
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_batch WHERE project_id = ? AND kind = 'global'",
                Long.class, projectId);
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 1);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, source_name, checksum, status, total_rows, new_count, updated_count,
                       unchanged_count, deprecated_count, created_at, committed_at
                  FROM import_batch
                 WHERE project_id = ? AND kind = 'global'
                 ORDER BY id DESC LIMIT ? OFFSET ?
                """, projectId, safeSize, (safePage - 1) * safeSize);
        return new PageResult<>(rows, total == null ? 0 : total, safePage, safeSize);
    }

    /** 错误行报告：只返回非 UNCHANGED 的失败/变更说明，供下载 CSV 文本。 */
    public String errorReport(long batchId) {
        access.requireGlobalEnter();
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT row_no, case_code, action, message FROM import_item
                 WHERE batch_id = ? AND action <> 'UNCHANGED' ORDER BY row_no
                """, batchId);
        StringBuilder csv = new StringBuilder("行号,用例编号,动作,说明\n");
        for (Map<String, Object> item : items) {
            csv.append(item.get("row_no")).append(',')
                    .append(csvCell(item.get("case_code"))).append(',')
                    .append(item.get("action")).append(',')
                    .append(csvCell(item.get("message"))).append('\n');
        }
        return csv.toString();
    }

    /** FR-49-30：按分类筛选导出 CSV，每行含 external_id，数据行数 = 筛选条数。 */
    public ExportCsv exportCategoryCsv(Long projectId, String categoryL1, String categoryL2,
                                       Long intersectWithSetId) {
        requireScopeRead(projectId);
        long scope = projectId == null ? platform.id() : projectId;
        List<Object> args = new ArrayList<>();
        args.add(scope);
        String where = categoryWhere(
                new CategoryFilter(categoryL1, categoryL2, intersectWithSetId), args);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT external_id, case_code, title, category_l1, category_l2, priority,"
                        + " a_side_command, b_side_command, switch_config, test_result, test_resource"
                        + " FROM test_case WHERE project_id = ?" + where
                        + " ORDER BY category_l1, case_code", args.toArray());
        StringBuilder csv = new StringBuilder(
                "external_id,case_code,title,category_l1,category_l2,priority,"
                        + "a_side_command,b_side_command,switch_config,test_result,test_resource\n");
        for (Map<String, Object> r : rows) {
            csv.append(csvCell(text(r.get("external_id"), r.get("case_code")))).append(',')
                    .append(csvCell(r.get("case_code"))).append(',')
                    .append(csvCell(r.get("title"))).append(',')
                    .append(csvCell(r.get("category_l1"))).append(',')
                    .append(csvCell(r.get("category_l2"))).append(',')
                    .append(csvCell(r.get("priority"))).append(',')
                    .append(csvCell(r.get("a_side_command"))).append(',')
                    .append(csvCell(r.get("b_side_command"))).append(',')
                    .append(csvCell(r.get("switch_config"))).append(',')
                    .append(csvCell(r.get("test_result"))).append(',')
                    .append(csvCell(r.get("test_resource"))).append('\n');
        }
        byte[] content = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha = sha256Hex(content).substring(0, 12);
        return new ExportCsv("cases-" + sha + ".csv", content, rows.size());
    }

    /** FR-49-28：三种筛选口径的公共 WHERE 片段（占位参数按序追加到 args）。 */
    public static String categoryWhere(CategoryFilter filter, List<Object> args) {
        if (filter == null) {
            return "";
        }
        StringBuilder where = new StringBuilder();
        if (notBlank(filter.categoryL1())) {
            where.append(" AND category_l1 = ?");
            args.add(filter.categoryL1().trim());
        }
        if (notBlank(filter.categoryL2())) {
            where.append(" AND category_l2 = ?");
            args.add(filter.categoryL2().trim());
        }
        if (filter.intersectWithSetId() != null && filter.intersectWithSetId() > 0) {
            where.append(" AND id IN (SELECT test_case_id FROM test_set_item"
                    + " WHERE test_set_id = ?)");
            args.add(filter.intersectWithSetId());
        }
        return where.toString();
    }

    /**
     * FR-49-27：幂等回填（与 V11_42 同口径，可重复执行）。只回填仍为 NULL 的行；
     * 字符串唯一匹配（HAVING COUNT(*)=1）才写 id，非唯一匹配不写入并标记
     * category_match_review='AMBIGUOUS' 待人工复核，绝不制造脏数据。
     */
    public int backfillCategoryIds(long projectId) {
        int l1 = jdbc.update("""
                UPDATE test_case t
                  JOIN (SELECT c.project_id, c.name, MIN(c.id) AS cat_id
                          FROM case_category c WHERE c.level = 1 AND c.project_id = ?
                         GROUP BY c.project_id, c.name HAVING COUNT(*) = 1) m
                    ON m.project_id = t.project_id AND m.name = t.category_l1
                   SET t.category_l1_id = m.cat_id
                 WHERE t.project_id = ? AND t.category_l1_id IS NULL
                """, projectId, projectId);
        int l2 = jdbc.update("""
                UPDATE test_case t
                  JOIN (SELECT c.project_id, c.name, MIN(c.id) AS cat_id
                          FROM case_category c WHERE c.level = 2 AND c.project_id = ?
                         GROUP BY c.project_id, c.name HAVING COUNT(*) = 1) m
                    ON m.project_id = t.project_id AND m.name = t.category_l2
                   SET t.category_l2_id = m.cat_id
                 WHERE t.project_id = ? AND t.category_l2_id IS NULL
                """, projectId, projectId);
        jdbc.update("""
                UPDATE test_case t
                  JOIN (SELECT c.project_id, c.name
                          FROM case_category c WHERE c.level = 1 AND c.project_id = ?
                         GROUP BY c.project_id, c.name HAVING COUNT(*) > 1) m
                    ON m.project_id = t.project_id AND m.name = t.category_l1
                   SET t.category_match_review = 'AMBIGUOUS'
                 WHERE t.project_id = ? AND t.category_l1_id IS NULL
                """, projectId, projectId);
        jdbc.update("""
                UPDATE test_case t
                  JOIN (SELECT c.project_id, c.name
                          FROM case_category c WHERE c.level = 2 AND c.project_id = ?
                         GROUP BY c.project_id, c.name HAVING COUNT(*) > 1) m
                    ON m.project_id = t.project_id AND m.name = t.category_l2
                   SET t.category_match_review = 'AMBIGUOUS'
                 WHERE t.project_id = ? AND t.category_l2_id IS NULL
                   AND t.category_match_review IS NULL
                """, projectId, projectId);
        return l1 + l2;
    }

    // ---------------------------------------------------------------- 内部

    private record PlannedRow(int rowNum, String externalId, String action, String message,
                              String contentSha, String sheet, int sourceRow,
                              CaseImportService.ImportRow row) {
    }

    private record ExistingCase(long id, String caseCode, String externalId, String contentSha,
                                String lifecycleStatus, int versionNo, String title,
                                String categoryL1, String categoryL2, String priority,
                                String safetyLevel, String sourceSheet, int sourceRow) {
    }

    private Map<String, ExistingCase> existingByExternal(long projectId) {
        Map<String, ExistingCase> map = new HashMap<>();
        jdbc.query("""
                SELECT id, case_code, external_id, content_sha, lifecycle_status, version_no,
                       title, category_l1, category_l2, priority, safety_level, source_sheet, 0 AS source_row
                  FROM test_case WHERE project_id = ? AND external_id IS NOT NULL
                """, rs -> {
            ExistingCase item = new ExistingCase(rs.getLong("id"), rs.getString("case_code"),
                    rs.getString("external_id"), rs.getString("content_sha"),
                    rs.getString("lifecycle_status"), rs.getInt("version_no"),
                    rs.getString("title"), rs.getString("category_l1"), rs.getString("category_l2"),
                    rs.getString("priority"), rs.getString("safety_level"),
                    rs.getString("source_sheet"), rs.getInt("source_row"));
            map.put(item.externalId().toUpperCase(Locale.ROOT), item);
        }, projectId);
        return map;
    }

    private ExistingCase findExisting(long projectId, String externalId) {
        List<ExistingCase> found = jdbc.query("""
                SELECT id, case_code, external_id, content_sha, lifecycle_status, version_no,
                       title, category_l1, category_l2, priority, safety_level, source_sheet, 0 AS source_row
                  FROM test_case WHERE project_id = ? AND external_id = ?
                """, (rs, i) -> new ExistingCase(rs.getLong("id"), rs.getString("case_code"),
                rs.getString("external_id"), rs.getString("content_sha"),
                rs.getString("lifecycle_status"), rs.getInt("version_no"),
                rs.getString("title"), rs.getString("category_l1"), rs.getString("category_l2"),
                rs.getString("priority"), rs.getString("safety_level"),
                rs.getString("source_sheet"), rs.getInt("source_row")), projectId, externalId);
        return found.isEmpty() ? null : found.get(0);
    }

    private static int count(List<PlannedRow> rows, String action) {
        return (int) rows.stream().filter(row -> action.equals(row.action())).count();
    }

    /** 内容指纹：只含业务字段，不含行号/来源，保证同一内容重复导入判定为 UNCHANGED。 */
    static String contentSha(CaseImportService.ImportRow row, String externalId) {
        String base = String.join("\u0001",
                nz(externalId), nz(row.title()), nz(row.category()), nz(row.categoryL2()),
                nz(row.priority()), nz(row.precondition()), nz(row.expected()),
                nz(row.caseClass()), nz(row.testCategory()), nz(row.ed202Coverage()),
                nz(row.safetyLevel()), String.join("\u0002", row.steps() == null
                        ? List.of() : row.steps()));
        // FR-49-05：补录列参与指纹，内容变化才触发 UPDATE；无补录列的旧台账 base 不变，
        // 存量 content_sha 不被整体改写，保证同一文件重复导入不新增版本。
        boolean hasExtra = notBlank(row.aSideCommand()) || notBlank(row.bSideCommand())
                || notBlank(row.switchConfig()) || notBlank(row.testResult())
                || notBlank(row.testResource());
        String canonical = hasExtra
                ? base + "\u0001" + String.join("\u0001", nz(row.aSideCommand()),
                        nz(row.bSideCommand()), nz(row.switchConfig()),
                        nz(row.testResult()), nz(row.testResource()))
                : base;
        return sha256Hex(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private static String text(Object... values) {
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private static String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    private static String nz(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** safety_level 为 NOT NULL DEFAULT 'LX'：导入缺省即「未判定」，不能显式写 NULL。 */
    private static String safetyLevel(String value) {
        return notBlank(value) ? value.trim() : "LX";
    }
}
