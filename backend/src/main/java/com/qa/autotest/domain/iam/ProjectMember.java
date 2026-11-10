package com.qa.autotest.domain.iam;

import java.util.List;

/**
 * 项目成员关系（行级隔离的依据）。
 *
 * @param roleCode    主角色：admin / pm / engineer / viewer（展示与排序用）
 * @param displayName 用户显示名（成员表用，来自 app_user）
 * @param enabled     用户是否启用；停用用户仍保留成员关系，界面标「已停用」
 * @param roleCodes   全部项目角色（v4.4.0 / FR-v4.4-01）；至少包含主角色
 */
public record ProjectMember(
        Long id,
        Long projectId,
        Long userId,
        String username,
        String roleCode,
        String displayName,
        boolean enabled,
        List<String> roleCodes) {

    /** 兼容旧七参构造：角色集合只含主角色。 */
    public ProjectMember(Long id, Long projectId, Long userId, String username,
                         String roleCode, String displayName, boolean enabled) {
        this(id, projectId, userId, username, roleCode, displayName, enabled,
                roleCode == null ? List.of() : List.of(roleCode));
    }
}
