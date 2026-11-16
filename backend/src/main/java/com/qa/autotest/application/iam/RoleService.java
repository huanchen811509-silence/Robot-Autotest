package com.qa.autotest.application.iam;

import com.qa.autotest.application.repository.RoleRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.iam.Role;
import com.qa.autotest.domain.iam.RoleCode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/** 角色 CRUD（IAM-01）：内置角色只读，不可删除或改 code。 */
@Service
public class RoleService {

    private static final Logger LOG = LoggerFactory.getLogger(RoleService.class);

    /**
     * 内置角色的中文名与说明（v5.21.0 / FR-v5.21-01 / W71-01）。
     *
     * <p>与迁移 `V11_80__v5_21_builtin_roles.sql` **同源同值**：迁移负责首次落库，
     * 这里只负责"运行期发现缺行就补齐"。两处都写一遍是有意的——迁移在已有环境里跑过，
     * 但有人手工删行、或从旧备份恢复只带了 admin 时，只有启动自检能救回来。
     * 真源仍是这份常量；迁移里的中文名改这里时必须同步改迁移（机检 tools/check_builtin_roles.py 守）。
     */
    public static final java.util.Map<RoleCode, String[]> BUILTIN_ROLES = java.util.Map.of(
            RoleCode.ADMIN, new String[]{"管理员", "平台全部权限"},
            RoleCode.PM, new String[]{"项目经理", "可管理自己项目的用例、资产与执行；可建项目"},
            RoleCode.ENGINEER, new String[]{"工程师", "可编写与执行用例、维护资产；不能建项目、不能管用户"},
            RoleCode.VIEWER, new String[]{"只读访客", "只读：可看用例、报告与环境，不能做任何写操作"});

    private final RoleRepository roles;
    private final AccessControlService access;
    private final AuditService audit;

    public RoleService(RoleRepository roles, AccessControlService access, AuditService audit) {
        this.roles = roles;
        this.access = access;
        this.audit = audit;
    }

    public List<Role> list() {
        access.require(PermissionResource.USER_ROLE, PermissionAction.READ);
        return roles.findAll();
    }

    /** 内置角色定义（供「新建用户」下拉在无 USER_ROLE 读权限时也能拿到中文名）。 */
    public static String labelOf(RoleCode code) {
        String[] def = BUILTIN_ROLES.get(code);
        return def == null ? code.code() : def[0];
    }

    /**
     * 启动自检（v5.21.0 / FR-v5.21-01 / W71-01）：四个内置角色必须在 `role` 表里存在。
     *
     * <p>**只补缺，不覆盖**：已存在的行连 `name` 都不碰——管理员手工把「项目经理」改成
     * 「PM」是合法操作，启动时改回去等于每次重启都回滚人工配置。
     *
     * <p>失败不阻断启动：角色缺行时系统仍能跑（只是界面上选不到该角色），
     * 直接让服务起不来会把一个数据问题升级成可用性事故。所以这里 catch 住只打日志。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            List<String> added = ensureBuiltinRoles();
            if (!added.isEmpty()) {
                LOG.warn("内置角色缺失，已补齐：{}（建议核对 role 表是否被手工清理过）", added);
            }
        } catch (RuntimeException ex) {
            LOG.warn("内置角色自检失败（不阻断启动）：{}", ex.getMessage());
        }
    }

    /** 补齐缺失的内置角色，返回本次新增的 code 列表（全部存在时返回空列表）。 */
    public List<String> ensureBuiltinRoles() {
        List<String> added = new ArrayList<>();
        for (RoleCode code : RoleCode.values()) {
            if (roles.codeExists(code.code(), null)) {
                continue;
            }
            String[] def = BUILTIN_ROLES.get(code);
            roles.insert(new Role(null, code.code(), def[0], def[1], true, null));
            added.add(code.code());
        }
        return added;
    }

    public Role create(String code, String name, String description, String permissionsJson) {
        access.require(PermissionResource.USER_ROLE, PermissionAction.CREATE);
        if (code == null || code.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "角色编码不能为空");
        }
        if (roles.codeExists(code, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "角色编码已存在：" + code);
        }
        long id = roles.insert(new Role(null, code.trim(), name, description, false, permissionsJson));
        audit.record("role.create", "role", String.valueOf(id), "code=" + code);
        return roles.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在"));
    }

    @Audited(action = "role:update", resource = "role")
    public Role update(long id, String name, String description, String permissionsJson) {
        access.require(PermissionResource.USER_ROLE, PermissionAction.UPDATE);
        Role role = roles.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在"));
        if (role.builtIn()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "内置角色不允许修改");
        }
        String before = "code=" + role.code() + "; name=" + role.name();
        roles.update(id, name, description, permissionsJson, TenantContext.userId());
        audit.recordChange("role:update", "role", String.valueOf(id), before, "name=" + name);
        return roles.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在"));
    }

    public void delete(long id) {
        access.require(PermissionResource.USER_ROLE, PermissionAction.DELETE);
        Role role = roles.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "角色不存在"));
        if (role.builtIn()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "内置角色不允许删除");
        }
        roles.delete(id);
        audit.record("role.delete", "role", String.valueOf(id), "code=" + role.code());
    }
}
