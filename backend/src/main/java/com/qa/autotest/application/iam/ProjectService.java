package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.application.repository.UserRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.iam.Project;
import com.qa.autotest.domain.iam.ProjectMember;
import com.qa.autotest.domain.iam.RoleCode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目与成员管理（IAM-01 / IAM-03）。
 *
 * <p><b>权限双轨口径（PRD §3.8）</b>：有效权限 = 全局角色 ∪ 项目角色（并集，v4.4.0）。
 * 成员表只展示项目内角色，不展示全局角色，避免用户把两种角色混为一谈。
 *
 * <p><b>owner 保护</b>：项目 owner 的成员关系不可删除、其项目角色不可降级（非 admin 一律 409），
 * 需要先转移所有权（{@code PUT /api/projects/{id}} 的 {@code ownerId}）。
 * <b>最后经理保护</b>：移除/降权最后一名 admin/pm 返回 409 {@code LAST_PM_REQUIRED}。
 */
@Service
public class ProjectService {

    /** 成员批量添加入参：一个用户可带 1~3 个项目角色。 */
    public record MemberInput(Long userId, String roleCode, List<String> roleCodes) {
        public List<String> effectiveRoles() {
            if (roleCodes != null && !roleCodes.isEmpty()) {
                return roleCodes;
            }
            return roleCode == null ? List.of() : List.of(roleCode);
        }
    }

    /** 逐人失败原因（批量添加不整体回滚）。 */
    public record MemberFailure(Long userId, String reason) {
    }

    public record BatchMembersResult(int added, int failed, List<MemberFailure> failures) {
    }

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final ProjectRepository projects;
    private final UserRepository users;
    private final AccessControlService access;
    private final AuditService audit;
    /** v2.7 FR-04：test_kernel 标注列与 kernel.default 路由真源同步（null = 旧构造/单测）。 */
    private final com.qa.autotest.application.system.SystemSettingService settings;

    public ProjectService(ProjectRepository projects, UserRepository users,
                          AccessControlService access, AuditService audit) {
        this(projects, users, access, audit, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProjectService(ProjectRepository projects, UserRepository users,
                          AccessControlService access, AuditService audit,
                          com.qa.autotest.application.system.SystemSettingService settings) {
        this.projects = projects;
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.settings = settings;
    }

    /** 当前用户可见的项目（管理员全部，其他人按成员关系）。 */
    public List<Project> visible() {
        return access.visibleProjects();
    }

    /** 是否可进入全局模式（FR-v4.4-06）。 */
    public boolean canEnterGlobal(long userId) {
        return access.canEnterGlobal(userId);
    }

    public Project get(long projectId) {
        access.bindProject(projectId);
        return projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
    }

    @Audited(action = "project:create", resource = "project")
    @Transactional
    public Project create(String code, String name, String description, String visibility,
                          String testKernel) {
        return create(code, name, description, visibility, testKernel, "standard");
    }

    @Transactional
    public Project create(String code, String name, String description, String visibility,
                          String testKernel, String kind) {
        access.require(PermissionResource.PROJECT, PermissionAction.CREATE);
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "项目编码不能为空");
        }
        if (projects.codeExists(code.trim(), null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "项目编码已存在：" + code);
        }
        long ownerId = TenantContext.requireUserId();
        long id = projects.insert(new Project(null, code.trim(), name, description, true,
                ownerId, visibility == null || visibility.isBlank() ? "private" : visibility,
                normalizeKernel(testKernel), kind == null ? "standard" : kind, null));
        projects.addMember(id, ownerId, RoleCode.ADMIN.code(), ownerId);
        syncKernelSetting(id, normalizeKernel(testKernel));
        audit.recordChange("project:create", "project", String.valueOf(id), null,
                "code=" + code + "; name=" + name + "; owner=" + ownerId);
        return projects.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
    }

    public Project update(long projectId, String name, String description, boolean enabled,
                          Long ownerId, String visibility, String testKernel) {
        access.bindProject(projectId);
        access.require(PermissionResource.PROJECT, PermissionAction.UPDATE);
        Project existing = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
        if (existing.platform()) {
            if (!enabled) {
                throw new BusinessException(ErrorCode.CONFLICT, "平台项目不可停用");
            }
            if (ownerId != null && !ownerId.equals(existing.ownerId())) {
                throw new BusinessException(ErrorCode.CONFLICT, "平台项目不可转移所有权");
            }
        }
        String kernel = normalizeKernel(testKernel);
        projects.update(projectId, name, description, enabled, ownerId, visibility,
                kernel, TenantContext.userId());
        syncKernelSetting(projectId, kernel);
        audit.record("project.update", "project", String.valueOf(projectId),
                "name=" + name + "; kernel=" + kernel);
        return get(projectId);
    }

    /**
     * v2.7 FR-04：把 test_kernel 标注列同步到 {@code kernel.default} 项目设置——
     * 执行链路由真源是 KernelSelector（读 kernel.default），不同步会出现
     * 「界面标 pytest、实际跑 robot」的脱节。best-effort：设置服务未装配（旧构造）时跳过。
     */
    private void syncKernelSetting(long projectId, String kernel) {
        if (settings != null) {
            settings.putProject(projectId, java.util.Map.of(
                    com.qa.autotest.application.system.SystemSettingService.P_DEFAULT_KERNEL,
                    kernel));
        }
    }

    /** 测试内核归一：空值回 robot（RF 是平台主内核），其余只认 robot / pytest。 */
    static String normalizeKernel(String testKernel) {
        if (testKernel == null || testKernel.isBlank()) {
            return "robot";
        }
        String value = testKernel.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"robot".equals(value) && !"pytest".equals(value)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "不支持的测试内核：" + testKernel + "（仅 robot / pytest）");
        }
        return value;
    }

    /**
     * 建议项目编码：{@code 前缀-RF|PYT-yyyyMM-序号}（FR-v4.4-07）。
     * 序号由 {@code project_code_sequence} 原子分配；已存在的编码继续向后找，保证不撞号。
     */
    @Transactional
    public String nextCode(String prefix, String kernel) {
        String normalizedPrefix = normalizePrefix(prefix);
        String scope = normalizedPrefix + "-" + kernelAbbr(kernel) + "-"
                + LocalDate.now(ZoneId.of("Asia/Shanghai")).format(MONTH);
        for (int attempt = 0; attempt < 100; attempt++) {
            long no = projects.nextCodeSequence(scope);
            String code = scope + "-" + String.format(Locale.ROOT, "%03d", no);
            if (!projects.codeExists(code, null)) {
                return code;
            }
        }
        throw new BusinessException(ErrorCode.CONFLICT, "编码序号分配失败，请重试");
    }

    static String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "");
        return value.isEmpty() ? "PRJ" : value;
    }

    static String kernelAbbr(String kernel) {
        return "pytest".equals(normalizeKernel(kernel)) ? "PYT" : "RF";
    }

    /** 编码唯一一次变更（FR-v4.4-08）：仅 ADMIN、必须 reason、写审计。 */
    @Transactional
    public Project changeCode(long projectId, String newCode, String reason) {
        long userId = TenantContext.requireUserId();
        if (!access.isAdmin(userId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可变更项目编码");
        }
        Project existing = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
        if (existing.platform()) {
            throw new BusinessException(ErrorCode.CONFLICT, "平台项目编码不可变更");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "变更编码必须填写原因");
        }
        if (newCode == null || newCode.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "新编码不能为空");
        }
        String normalized = newCode.trim();
        if (normalized.equals(existing.code())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "新编码与当前编码相同");
        }
        if (existing.codeChangedAt() != null) {
            throw new BusinessException(ErrorCode.CODE_ALREADY_CHANGED,
                    "项目编码只能变更一次，当前项目已变更过");
        }
        if (projects.codeExists(normalized, projectId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "项目编码已存在：" + normalized);
        }
        if (!projects.changeCode(projectId, normalized, reason, userId)) {
            throw new BusinessException(ErrorCode.CODE_ALREADY_CHANGED,
                    "项目编码只能变更一次，当前项目已变更过");
        }
        audit.recordChange("project.code.change", "project", String.valueOf(projectId),
                "code=" + existing.code(), "code=" + normalized + "; reason=" + reason.trim());
        return projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
    }

    @Audited(action = "project:delete", resource = "project")
    public void delete(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.PROJECT, PermissionAction.DELETE);
        Project existing = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
        if (existing.platform()) {
            throw new BusinessException(ErrorCode.CONFLICT, "平台项目不可删除");
        }
        Map<String, Long> busy = projects.busyCounts(projectId);
        if (!busy.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "项目下仍" + describeBusy(busy) + "，只能停用（归档），不能彻底删除");
        }
        projects.delete(projectId);
        audit.recordChange("project:delete", "project", String.valueOf(projectId),
                "code=" + existing.code() + "; name=" + existing.name(),
                null);
    }

    /** 删除前置检查的表名 → 中文名（常见表给友好名，其余回退表名）；顺序由仓储按表名排序。 */
    private static final Map<String, String> BUSY_LABELS = new LinkedHashMap<>();

    static {
        BUSY_LABELS.put("test_case", "用例");
        BUSY_LABELS.put("test_set", "用例集");
        BUSY_LABELS.put("test_scenario", "测试场景");
        BUSY_LABELS.put("run", "运行");
        BUSY_LABELS.put("test_report", "报告");
        BUSY_LABELS.put("device", "设备");
        BUSY_LABELS.put("device_host_spec", "主机清单");
        BUSY_LABELS.put("link", "链路");
        BUSY_LABELS.put("credential", "凭据");
        BUSY_LABELS.put("project_asset", "资产引用");
        BUSY_LABELS.put("asset_version", "资产版本");
        BUSY_LABELS.put("env_profile", "环境");
        BUSY_LABELS.put("topology_node", "拓扑节点");
        BUSY_LABELS.put("robot_file", "文件");
        BUSY_LABELS.put("workspace_job", "工作区任务");
        BUSY_LABELS.put("schedule", "调度");
        BUSY_LABELS.put("execution_job", "执行作业");
        BUSY_LABELS.put("execution_plan", "执行计划");
        BUSY_LABELS.put("plan_group", "计划组");
        BUSY_LABELS.put("export_record", "导出记录");
        BUSY_LABELS.put("ci_trigger", "CI 触发");
        BUSY_LABELS.put("saved_view", "保存视图");
        BUSY_LABELS.put("notification", "通知");
        BUSY_LABELS.put("alert_rule", "告警规则");
        BUSY_LABELS.put("ai_session", "AI 会话");
        BUSY_LABELS.put("report_template", "报告模板");
        BUSY_LABELS.put("resource_lease", "资源租约");
        BUSY_LABELS.put("case_source", "用例来源");
        BUSY_LABELS.put("import_batch", "导入批次");
        BUSY_LABELS.put("retention_policy", "保留策略");
        BUSY_LABELS.put("archive_policy", "归档策略");
        BUSY_LABELS.put("archived_run", "归档运行");
        BUSY_LABELS.put("api_token", "项目令牌");
    }

    private static String describeBusy(Map<String, Long> busy) {
        StringBuilder text = new StringBuilder("有");
        busy.forEach((code, count) -> {
            if (text.length() > 1) {
                text.append("、");
            }
            text.append(BUSY_LABELS.getOrDefault(code, code)).append(' ').append(count)
                    .append(" 条");
        });
        return text.toString();
    }

    public List<ProjectMember> members(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.PROJECT, PermissionAction.READ);
        return projects.members(projectId);
    }

    @Audited(action = "member:update", resource = "project_member")
    @Transactional
    public ProjectMember addMember(long projectId, long userId, String roleCode) {
        return addMember(projectId, userId, List.of(roleCode));
    }

    @Transactional
    public ProjectMember addMember(long projectId, long userId, List<String> roleCodes) {
        access.bindProject(projectId);
        access.require(PermissionResource.PROJECT, PermissionAction.UPDATE);
        users.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "用户不存在"));
        List<String> roles = normalizeRoles(roleCodes);
        if (projects.findMember(projectId, userId).isPresent()) {
            throw new BusinessException(ErrorCode.CONFLICT, "该用户已是项目成员");
        }
        projects.addMember(projectId, userId, primaryRole(roles), TenantContext.userId());
        projects.replaceMemberRoles(projectId, userId, roles, primaryRole(roles),
                TenantContext.userId());
        audit.recordChange("member.roles.update", "project_member", projectId + ":" + userId,
                "roles=[]", "roles=" + roles);
        return projects.findMember(projectId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "成员不存在"));
    }

    /** 批量添加（FR-v4.4-03）：逐人去重、逐人校验，单个失败不影响其他人。 */
    @Transactional
    public BatchMembersResult addMembers(long projectId, List<MemberInput> inputs) {
        access.bindProject(projectId);
        access.require(PermissionResource.PROJECT, PermissionAction.UPDATE);
        int added = 0;
        List<MemberFailure> failures = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        if (inputs != null) {
            for (MemberInput input : inputs) {
                if (input == null || input.userId() == null) {
                    continue;
                }
                if (!seen.add(input.userId())) {
                    continue;
                }
                try {
                    addMember(projectId, input.userId(), input.effectiveRoles());
                    added++;
                } catch (BusinessException ex) {
                    String reason = ex.getMessage();
                    if (ex.errorCode() == ErrorCode.CONFLICT && reason != null
                            && reason.contains("已是项目成员")) {
                        // 重复勾选/已存在按成功幂等处理，不计失败
                        continue;
                    }
                    failures.add(new MemberFailure(input.userId(), reason));
                }
            }
        }
        audit.record("project.member.batch", "project_member", String.valueOf(projectId),
                "added=" + added + "; failed=" + failures.size());
        return new BatchMembersResult(added, failures.size(), failures);
    }

    @Audited(action = "member:update", resource = "project_member")
    public ProjectMember updateMemberRole(long projectId, long userId, String roleCode) {
        return updateMemberRoles(projectId, userId, List.of(roleCode));
    }

    /** 行内多角色保存（FR-v4.4-04）：覆盖式写入，主角色按优先级归一。 */
    @Transactional
    public ProjectMember updateMemberRoles(long projectId, long userId, List<String> roleCodes) {
        access.bindProject(projectId);
        access.require(PermissionResource.PROJECT, PermissionAction.UPDATE);
        ProjectMember member = projects.findMember(projectId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "成员不存在"));
        List<String> roles = normalizeRoles(roleCodes);
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
        if (project.ownerId() != null && project.ownerId() == userId
                && !roles.contains(RoleCode.ADMIN.code())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "不能降级项目所有者；如需变更请先转移所有权");
        }
        ensureManagerRemains(projectId, member, roles);
        projects.replaceMemberRoles(projectId, userId, roles, primaryRole(roles),
                TenantContext.userId());
        audit.recordChange("member.roles.update", "project_member", projectId + ":" + userId,
                "roles=" + member.roleCodes(), "roles=" + roles);
        return projects.findMember(projectId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "成员不存在"));
    }

    @Transactional
    public void removeMember(long projectId, long userId) {
        access.bindProject(projectId);
        access.require(PermissionResource.PROJECT, PermissionAction.UPDATE);
        Project project = projects.findById(projectId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"));
        if (project.ownerId() != null && project.ownerId() == userId) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "不能移除项目所有者；如需变更请先转移所有权");
        }
        ProjectMember member = projects.findMember(projectId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "成员不存在"));
        ensureManagerRemains(projectId, member, List.of());
        projects.removeMember(projectId, userId);
        audit.recordChange("member.roles.update", "project_member", projectId + ":" + userId,
                "roles=" + member.roleCodes(), "roles=[]");
    }

    private void ensureManagerRemains(long projectId, ProjectMember member, List<String> newRoles) {
        boolean wasManager = member.roleCodes().stream()
                .anyMatch(code -> code.equals(RoleCode.ADMIN.code())
                        || code.equals(RoleCode.PM.code()));
        boolean staysManager = newRoles.stream()
                .anyMatch(code -> code.equals(RoleCode.ADMIN.code())
                        || code.equals(RoleCode.PM.code()));
        if (wasManager && !staysManager && projects.countManagers(projectId) <= 1) {
            throw new BusinessException(ErrorCode.LAST_PM_REQUIRED,
                    "项目必须保留至少一名项目经理（LAST_PM_REQUIRED）");
        }
    }

    private static List<String> normalizeRoles(List<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "项目角色不能为空");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String roleCode : roleCodes) {
            if (roleCode == null || roleCode.isBlank()) {
                continue;
            }
            try {
                unique.add(RoleCode.from(roleCode).code());
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "非法项目角色：" + roleCode);
            }
        }
        if (unique.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "项目角色不能为空");
        }
        List<String> ordered = new ArrayList<>();
        for (RoleCode role : List.of(RoleCode.ADMIN, RoleCode.PM, RoleCode.ENGINEER,
                RoleCode.VIEWER)) {
            if (unique.contains(role.code())) {
                ordered.add(role.code());
            }
        }
        return ordered;
    }

    private static String primaryRole(List<String> roles) {
        for (RoleCode role : List.of(RoleCode.ADMIN, RoleCode.PM, RoleCode.ENGINEER,
                RoleCode.VIEWER)) {
            if (roles.contains(role.code())) {
                return role.code();
            }
        }
        return RoleCode.VIEWER.code();
    }

    private static String normalizeRole(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "项目角色不能为空");
        }
        try {
            return RoleCode.from(roleCode).code();
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "非法项目角色：" + roleCode);
        }
    }
}
