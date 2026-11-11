package com.qa.autotest.application.iam;

import com.qa.autotest.application.asset.ProjectAssetService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.iam.Project;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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

    /**
     * 模板详情：{@code items} 为兼容旧前端的扁平列表，另按类型给出四类明细数组
     * （v4.9.2 / OPT-05 / FR-49-17），四类直接由 {@code project_template_item} /
     * {@code project_template_asset} 的 SQL 行分组得到，与分组计数一致。
     */
    public record TemplateDetail(TemplateRow template, List<TemplateItem> items,
                                 List<TemplateItem> cases, List<TemplateItem> caseSets,
                                 List<TemplateItem> scenarios, List<TemplateItem> assets) {
    }

    public record TemplateRequest(String name, String code, String kernel, String visibility,
                                  String description) {
    }

    /** POST /api/global/templates 与模板清单更新入参：带四类明细，空 items 一律 4xx、不落库。 */
    public record CreateTemplateRequest(String name, String code, String kernel, String visibility,
                                        String description, List<TemplateItemInput> items) {
    }

    /** 明细项：itemType=CASE/CASE_SET/SCENARIO，或 DEVICE/CREDENTIAL/LINK（资产时 refId=assetId）。 */
    public record TemplateItemInput(String itemType, Long refId, String mode, String name,
                                    String snapshotJson) {
    }

    /** 内部行：project_template_item。 */
    protected record TemplateItemRow(String itemType, Long refId, String mode, String snapshotJson) {
    }

    /** 内部行：project_template_asset。 */
    protected record TemplateAssetRow(String assetType, long assetId) {
    }

    private final JdbcTemplate jdbc;
    private final AccessControlService access;
    private final AuditService audit;
    private final ProjectAssetService projectAssets;
    private final ProjectAssetRepository assetRepository;
    /** OPT-06：建项目必须复用其编码与 owner 成员初始化逻辑，绝不复制粘贴。 */
    private final ProjectService projectService;

    public ProjectTemplateService(JdbcTemplate jdbc, AccessControlService access, AuditService audit,
                                  ProjectAssetService projectAssets,
                                  ProjectAssetRepository assetRepository,
                                  ProjectService projectService) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
        this.projectAssets = projectAssets;
        this.assetRepository = assetRepository;
        this.projectService = projectService;
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
        List<TemplateItem> cases = new ArrayList<>();
        List<TemplateItem> caseSets = new ArrayList<>();
        List<TemplateItem> scenarios = new ArrayList<>();
        List<TemplateItem> assets = new ArrayList<>();
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
            TemplateItem item = new TemplateItem(rs.getString("item_type"),
                    rs.getObject("ref_id") == null ? null : rs.getLong("ref_id"),
                    rs.getString("mode"), rs.getString("display_name"));
            items.add(item);
            switch (item.itemType() == null ? "" : item.itemType()) {
                case "CASE" -> cases.add(item);
                case "CASE_SET" -> caseSets.add(item);
                case "SCENARIO" -> scenarios.add(item);
                default -> { }
            }
        }, id);
        jdbc.query("""
                SELECT a.asset_type, a.asset_id,
                       COALESCE((SELECT d.name FROM device d WHERE a.asset_type = 'DEVICE' AND d.id = a.asset_id),
                                (SELECT c.name FROM credential c WHERE a.asset_type = 'CREDENTIAL' AND c.id = a.asset_id))
                       AS display_name
                  FROM project_template_asset a WHERE a.template_id = ?
                """, rs -> {
            TemplateItem item = new TemplateItem(rs.getString("asset_type"),
                    rs.getLong("asset_id"), "reference", rs.getString("display_name"));
            items.add(item);
            assets.add(item);
        }, id);
        return new TemplateDetail(row, items, cases, caseSets, scenarios, assets);
    }

    // ---------------------------------------------------------------- 增改删

    @Transactional
    public TemplateRow create(TemplateRequest request) {
        access.requireGlobalEnter();
        validate(request, null);
        long operator = TenantContext.requireUserId();
        String code = notBlank(request.code()) ? request.code().trim() : generateCode(request);
        try {
            long id = insertTemplateRow(request, code, operator);
            audit.record("template.create", "project_template", String.valueOf(id),
                    "operator=" + operator + ";templateId=" + id + ";code=" + code);
            return one(id);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板编号已存在：" + code);
        }
    }

    /**
     * POST /api/global/templates（OPT-05 / FR-49-17~20）：items 为空返回 4xx 且不落库；
     * 明细只写两张模板表（引用），绝不写回公共用例本体 {@code test_case}。
     */
    @Transactional
    public TemplateRow create(CreateTemplateRequest request) {
        access.requireGlobalEnter();
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "模板 items 不能为空");
        }
        TemplateRequest base = new TemplateRequest(request.name(), request.code(), request.kernel(),
                request.visibility(), request.description());
        validate(base, null);
        long operator = TenantContext.requireUserId();
        String code = notBlank(request.code()) ? request.code().trim() : generateCode(base);
        try {
            long id = insertTemplateRow(base, code, operator);
            insertItems(id, request.items());
            audit.record("template.create", "project_template", String.valueOf(id),
                    "operator=" + operator + ";templateId=" + id + ";code=" + code
                            + ";items=" + request.items().size());
            return one(id);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.CONFLICT, "模板编号或明细已存在：" + code);
        }
    }

    /**
     * 模板清单更新（FR-49-20/21）：builtin 模板内容改写返回 4xx 且 items 不变；
     * 重写明细只动两张模板表，绝不 UPDATE 公共用例本体。
     */
    @Transactional
    public TemplateRow replaceContent(long id, CreateTemplateRequest request) {
        access.requireGlobalEnter();
        TemplateRow current = one(id);
        if (current.builtin()) {
            throw new BusinessException(ErrorCode.CONFLICT, "内置模板内容不可改写，items 保持不变");
        }
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "模板 items 不能为空");
        }
        long operator = TenantContext.requireUserId();
        jdbc.update("DELETE FROM project_template_item WHERE template_id = ?", id);
        jdbc.update("DELETE FROM project_template_asset WHERE template_id = ?", id);
        insertItems(id, request.items());
        audit.record("template.update", "project_template", String.valueOf(id),
                "operator=" + operator + ";templateId=" + id + ";items=" + request.items().size());
        return one(id);
    }

    /** 只写两张模板表；资产项进 project_template_asset，其余进 project_template_item。 */
    private void insertItems(long templateId, List<TemplateItemInput> items) {
        int sort = 1;
        for (TemplateItemInput item : items) {
            if (item == null || item.refId() == null) {
                continue;
            }
            String type = item.itemType() == null ? "" : item.itemType().trim().toUpperCase(Locale.ROOT);
            if ("DEVICE".equals(type) || "CREDENTIAL".equals(type) || "LINK".equals(type)) {
                jdbc.update("""
                        INSERT INTO project_template_asset (template_id, asset_type, asset_id, mode)
                        VALUES (?, ?, ?, 'reference')
                        """, templateId, type, item.refId());
            } else {
                String mode = "copy".equalsIgnoreCase(item.mode()) ? "copy" : "reference";
                jdbc.update("""
                        INSERT INTO project_template_item
                            (template_id, item_type, ref_id, mode, sort_no, snapshot_json)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, templateId, type, item.refId(), mode, sort++,
                        item.snapshotJson() == null ? item.name() : item.snapshotJson());
            }
        }
    }

    /** 模板主表插入抽出为私有方法，两个 create 共用，避开 @Transactional 自调用歧义。 */
    private long insertTemplateRow(TemplateRequest request, String code, long operator) {
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
        return keys.getKey() == null ? 0L : keys.getKey().longValue();
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
        audit.record("template.update", "project_template", String.valueOf(id),
                "operator=" + TenantContext.requireUserId() + ";templateId=" + id);
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
        audit.record("template.delete", "project_template", String.valueOf(id),
                "operator=" + TenantContext.requireUserId() + ";templateId=" + id
                        + ";code=" + current.code());
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
                "operator=" + TenantContext.requireUserId() + ";templateId=" + created.id()
                        + ";from=" + id);
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
                "operator=" + TenantContext.requireUserId() + ";templateId=" + created.id()
                        + ";project=" + projectId);
        return one(created.id());
    }

    // ------------------------------------------------------------ 从模板建项目（OPT-06）

    /**
     * POST /api/projects/from-template（v4.9.2 / OPT-06 / FR-49-22~25）：<b>单事务</b>内完成
     * 「建项目 → 生成用例/用例集/场景 → 引用资产（写 project_asset）→ 写审计」。
     *
     * <p>建项目完整复用 {@link ProjectService#create} 的编码唯一性校验与 owner 成员初始化，
     * 不复制粘贴编码规则。{@code mode=copy} 时用例副本带独立 {@code content_sha}，与公共库解耦；
     * {@code reference} 为可选模式。所有写入走同一 {@link JdbcTemplate}（同一事务/连接），
     * 任一环节抛 {@link RuntimeException} 整体回滚，失败零残留。
     */
    @Transactional
    public Project createProjectFromTemplate(CreateProjectFromTemplateRequest request) {
        if (request == null || request.templateId() == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "templateId 不能为空");
        }
        if (!notBlank(request.name())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "项目名称不能为空");
        }
        access.require(PermissionResource.PROJECT, PermissionAction.CREATE);
        long operator = TenantContext.requireUserId();
        long templateId = request.templateId();
        String mode = request.resolvedMode();
        if (!CreateProjectFromTemplateRequest.MODE_COPY.equals(mode)
                && !CreateProjectFromTemplateRequest.MODE_REFERENCE.equals(mode)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "mode 只支持 copy / reference");
        }
        TemplateRow template = one(templateId);
        String kernel = normalizeKernel(template.kernel());
        // 编码复用 ProjectService.nextCode（同一编码规则）；显式 code 时直接用。
        String code = notBlank(request.code()) ? request.code().trim()
                : projectService.nextCode("PRJ", kernel);
        // 幂等 409：project 表无 template_id 列（本批不新建迁移），用 description 来源标记
        // "from-template:<id>" 圈定作用域；同 code 或同 name 二次提交即冲突，项目总数只 +1。
        String source = "from-template:" + templateId;
        if (duplicateProjectExists(source, code, request.name().trim())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "该模板已用于创建同名/同编码项目：" + request.name());
        }
        // ① 建项目（复用 ProjectService 的编码 + 成员初始化 + 审计），与后续步骤同一事务
        Project project = projectService.create(code, request.name().trim(), source, "private", kernel);
        long projectId = project.id();
        // ② 生成用例 / 用例集 / 场景（copy 模式复制正文并给 content_sha 独立指纹）
        generateContent(projectId, loadTemplateItems(templateId), mode, operator);
        // ③ 引用资产：写 project_asset（只读引用公共资产，不动物资本体）
        for (TemplateAssetRow asset : loadTemplateAssets(templateId)) {
            assetRepository.addReference(projectId, asset.assetType(), asset.assetId(), null, operator);
        }
        // 失败注入点：位于「写 project_asset 之后」，供单测验证整体回滚；生产为 no-op。
        afterTemplateAssetsReferenced(projectId);
        // ④ 审计（record 为同步写，与 ①②③ 同一事务，失败一并回滚）
        audit.record("template.create_project", "project", String.valueOf(projectId),
                "operator=" + operator + ";templateId=" + templateId
                        + ";projectId=" + projectId + ";mode=" + mode);
        return project;
    }

    /** 幂等 409 检查：protected 便于单测注入，生产走 project 表的来源标记计数。 */
    protected boolean duplicateProjectExists(String source, String code, String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM project WHERE description = ? AND (code = ? OR name = ?)",
                Integer.class, source, code, name);
        return count != null && count > 0;
    }

    /** 项目资产引用写入后的编排钩子：生产 no-op，单测覆盖抛 RuntimeException 验证单事务回滚。 */
    protected void afterTemplateAssetsReferenced(long projectId) {
        // 生产空实现
    }

    /** 读取模板内容项（CASE / CASE_SET / SCENARIO）。 */
    protected List<TemplateItemRow> loadTemplateItems(long templateId) {
        return jdbc.query("""
                SELECT item_type, ref_id, mode, snapshot_json
                  FROM project_template_item WHERE template_id = ? ORDER BY sort_no, id
                """, (rs, i) -> new TemplateItemRow(rs.getString("item_type"),
                rs.getObject("ref_id") == null ? null : rs.getLong("ref_id"),
                rs.getString("mode"), rs.getString("snapshot_json")), templateId);
    }

    /** 读取模板引用的公共资产（DEVICE / CREDENTIAL / LINK）。 */
    protected List<TemplateAssetRow> loadTemplateAssets(long templateId) {
        return jdbc.query("""
                SELECT asset_type, asset_id FROM project_template_asset WHERE template_id = ?
                """, (rs, i) -> new TemplateAssetRow(rs.getString("asset_type"),
                rs.getLong("asset_id")), templateId);
    }

    /**
     * 生成用例 / 用例集 / 场景：copy 模式复制用例正文并重写 {@code content_sha} 为独立指纹，
     * 改公共库不影响项目副本；reference 模式沿用公共用例 {@code content_sha}（保持关联）。
     * 全程只 INSERT 项目自有表，绝不 UPDATE 公共用例本体。
     */
    protected void generateContent(long projectId, List<TemplateItemRow> items, String mode,
                                   long operator) {
        List<Long> newCaseIds = new ArrayList<>();
        for (TemplateItemRow item : items) {
            if ("CASE".equals(item.itemType()) && item.refId() != null) {
                Long id = copyCaseIntoProject(projectId, item.refId(), mode, operator);
                if (id != null) {
                    newCaseIds.add(id);
                }
            }
        }
        // 本轮实际产出的用例集（含「模板导入用例集」与各 CASE_SET 项），
        // 场景的 case_set_ids 必须指向它们，否则场景详情永远是空的（L-49-07）。
        List<Long> createdSetIds = new ArrayList<>();
        if (!newCaseIds.isEmpty()) {
            long setId = createTestSet(projectId, "模板导入用例集", operator);
            createdSetIds.add(setId);
            int seq = 1;
            for (Long caseId : newCaseIds) {
                jdbc.update("""
                        INSERT INTO test_set_item
                            (test_set_id, test_case_id, seq_no, item_source, created_by, updated_by)
                        VALUES (?, ?, ?, 'template', ?, ?)
                        """, setId, caseId, seq++, operator, operator);
            }
        }
        for (TemplateItemRow item : items) {
            if ("CASE_SET".equals(item.itemType())) {
                createdSetIds.add(createTestSet(projectId, displayNameOf(item), operator));
            }
        }
        // 分两轮：先把所有用例集建完，场景才能拿到完整 id 串（模板里 SCENARIO 可能排在 CASE_SET 前面）。
        String caseSetIds = createdSetIds.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        for (TemplateItemRow item : items) {
            if ("SCENARIO".equals(item.itemType())) {
                jdbc.update("""
                        INSERT INTO test_scenario (project_id, name, case_set_ids, parent_scenario_id)
                        VALUES (?, ?, ?, NULL)
                        """, projectId, displayNameOf(item), caseSetIds);
            }
        }
    }

    private Long copyCaseIntoProject(long projectId, long sourceCaseId, String mode, long operator) {
        Long newId = copyCase(projectId, sourceCaseId, operator);
        if (newId != null && CreateProjectFromTemplateRequest.MODE_COPY.equals(mode)) {
            // 独立 content_sha：与公共库内容指纹解耦，改公共库不影响项目副本
            jdbc.update("UPDATE test_case SET content_sha = ? WHERE id = ?",
                    independentContentSha(projectId, newId), newId);
        }
        return newId;
    }

    private long createTestSet(long projectId, String name, long operator) {
        String setCode = "TS-" + UUID.randomUUID().toString().substring(0, 8);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO test_set (project_id, set_code, name, description, created_by, updated_by)
                    VALUES (?, ?, ?, '模板导入', ?, ?)
                    """, new String[]{"id"});
            ps.setLong(1, projectId);
            ps.setString(2, setCode);
            ps.setString(3, name);
            ps.setLong(4, operator);
            ps.setLong(5, operator);
            return ps;
        }, keys);
        return keys.getKey() == null ? 0L : keys.getKey().longValue();
    }

    private static String displayNameOf(TemplateItemRow item) {
        return item.snapshotJson() == null || item.snapshotJson().isBlank()
                ? item.itemType() + "-" + item.refId() : item.snapshotJson();
    }

    private String independentContentSha(long projectId, long caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT title, precondition, expected, description FROM test_case WHERE id = ?", caseId);
        String body = rows.isEmpty() ? String.valueOf(caseId) : String.valueOf(rows.get(0));
        return sha256(projectId + "|" + caseId + "|" + body);
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 为 JDK 必备算法，理论不可达；真不可达也不阻断建项
            return Integer.toHexString(text.hashCode());
        }
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

    /**
     * 复制一条公共用例到目标项目。
     *
     * <p>v4.10.0 修复（L-49-08）：原实现手写「27 个列名 + 23 个占位符」，其中 {@code category}
     * 列早已重命名为 {@code category_l1}，导致 {@code Unknown column 'category'} 运行期 500。
     * 该缺陷此前不可见——四个内置模板内容全为空（L-49-02），这段代码从未被执行过；
     * 补齐模板内容后立刻暴露。现改为 {@code INSERT ... SELECT} 整列搬迁，
     * 并顺带把 v4.10.0 新增的 A端/B端/交换机/测试结果/测试资源五列与两级分类 id 一并带上，
     * 避免「模板建项目」把补录内容丢掉。
     */
    private Long copyCase(long projectId, long sourceCaseId, long operator) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT case_code FROM test_case WHERE id = ?", sourceCaseId);
        if (rows.isEmpty()) {
            return null;
        }
        String code = String.valueOf(rows.get(0).get("case_code"));
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
                        review_status, source, capability, category_l1, category_l2, source_priority,
                        case_class, test_category, ed202_coverage, content_sha, lifecycle_status,
                        version_no, safety_level, a_side_command, b_side_command, switch_config,
                        test_result, test_resource, category_l1_id, category_l2_id,
                        category_match_review, created_by, updated_by)
                    SELECT ?, case_code, external_id, title, case_kind,
                        source_type, priority, domain_tag, status, description, precondition, expected,
                        'draft', 'import', capability, category_l1, category_l2, source_priority,
                        case_class, test_category, ed202_coverage, content_sha, 'active',
                        1, safety_level, a_side_command, b_side_command, switch_config,
                        test_result, test_resource, category_l1_id, category_l2_id,
                        category_match_review, ?, ?
                      FROM test_case WHERE id = ?
                    """, new String[]{"id"});
            ps.setLong(1, projectId);
            ps.setLong(2, operator);
            ps.setLong(3, operator);
            ps.setLong(4, sourceCaseId);
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

    protected TemplateRow one(long id) {
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
        audit.record("template.status", "project_template", String.valueOf(id),
                "operator=" + TenantContext.requireUserId() + ";templateId=" + id
                        + ";status=" + normalized);
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

    /** 供前端展示：模板项按类型聚合数量。 */
    public static Map<String, Integer> summarize(List<TemplateItem> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (TemplateItem item : items) {
            counts.merge(item.itemType(), 1, Integer::sum);
        }
        return counts;
    }
}
