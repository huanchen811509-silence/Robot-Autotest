package com.qa.autotest.application.iam;

import com.qa.autotest.application.asset.GlobalAssetService;
import com.qa.autotest.application.group.GroupService;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.iam.Project;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目向导「第 8 步一次性提交」（v5.10.0 / FR-v5.10-10 / W60-07，方案 A）。
 *
 * <p>为什么要有这一层：向导前七步只在前端持有状态，第 8 步一次落库。如果前端按顺序调
 * 六七个端点（建项目 → 加成员 → 组授权 → 内容 → 资产 → 凭证），任何一步失败都会留下
 * 半个项目——「项目建了但成员没进」这种半成品在列表里长得跟真项目一样，没人会去删。
 * 因此六类对象必须在**一个事务**里写：任一失败整体回滚，库里要么什么都没有，要么全都有。
 *
 * <p>几处刻意的取舍：
 * <ul>
 *   <li><b>成员不整体失败</b>：沿用既有批量成员口径（{@code BatchMembersResult.failures}），
 *       逐个回报失败原因。一个用户被禁用不该让整个项目建不出来，用户能看到谁没进去。</li>
 *   <li><b>组授权要求管理员</b>：{@code POST /groups/grant} 本就是管理员动作，
 *       这里不发明第二套判权，直接同口径拒绝。</li>
 *   <li><b>凭证绑定失败降级为警告</b>：绑定写的是**平台级设备**（两列共享），
 *       非全局权限的用户没有资格改它。为了不让整次创建因为「顺手勾了个凭证」而全盘失败，
 *       这里捕获并记警告——项目建出来了，凭证留给有权限的人在资产页补。</li>
 * </ul>
 */
@Service
public class ProjectCompositeService {

    /** 成员一行：用户 + 多角色。 */
    public record MemberSpec(Long userId, List<String> roleCodes) {
    }

    /** 组授权一行：组 + 该组在本项目的角色。 */
    public record GroupSpec(Long groupId, String roleCode) {
    }

    /** 资产一行：类型 + id + 两个地址面各自的凭证（null=不动，0=清空）。 */
    public record AssetSpec(String assetType, Long assetId, Long managementCredentialId,
                            Long bmcCredentialId) {
    }

    /** 一次提交的全部入参。 */
    public record CompositeRequest(String code, String name, String description, String visibility,
                                   String testKernel, String kind, Long ownerId, Boolean demo,
                                   List<MemberSpec> members, List<GroupSpec> groups,
                                   List<AssetSpec> assets, List<Long> caseIds,
                                   List<Long> caseSetIds, String setName,
                                   List<Long> scenarioIds) {
        /** 兼容旧调用：没有场景入参时语义与 5.16 完全一致。 */
        public CompositeRequest(String code, String name, String description, String visibility,
                                String testKernel, String kind, Long ownerId, Boolean demo,
                                List<MemberSpec> members, List<GroupSpec> groups,
                                List<AssetSpec> assets, List<Long> caseIds,
                                List<Long> caseSetIds, String setName) {
            this(code, name, description, visibility, testKernel, kind, ownerId, demo,
                    members, groups, assets, caseIds, caseSetIds, setName, null);
        }
    }

    /** 提交结果：项目 id 与逐类实际落地条数，供前端复核步对照。 */
    public record CompositeResult(long projectId, String code, int members, int groups, int assets,
                                  int cases, List<String> warnings) {
    }

    private final ProjectService projects;
    private final ProjectTemplateService templates;
    private final GroupService groups;
    private final GlobalAssetService assets;
    private final AccessControlService access;

    public ProjectCompositeService(ProjectService projects, ProjectTemplateService templates,
                                   GroupService groups, GlobalAssetService assets,
                                   AccessControlService access) {
        this.projects = projects;
        this.templates = templates;
        this.groups = groups;
        this.assets = assets;
        this.access = access;
    }

    @Transactional
    public CompositeResult create(CompositeRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "缺少创建参数");
        }
        // 判权前置：组授权是管理员动作，先拒再写。放在这里而不是「写到组那一步再拒」，
        // 是因为后置拒绝虽然靠事务能回滚，但会白跑一遍项目 + 成员 + 内容的写入链。
        boolean hasGroups = request.groups() != null && !request.groups().isEmpty();
        if (hasGroups && !access.isAdmin()) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "仅管理员可向用户组授权");
        }
        List<String> warnings = new ArrayList<>();
        boolean demo = Boolean.TRUE.equals(request.demo());

        // 1) 项目本身（负责人 + 演示标记），失败即整体失败
        Project project = projects.create(request.code(), request.name(), request.description(),
                request.visibility(), request.testKernel(), request.kind(),
                request.ownerId(), demo);
        long projectId = project.id();

        // 2) 成员：批量写，逐人失败进 warnings
        int memberCount = 0;
        if (request.members() != null && !request.members().isEmpty()) {
            List<ProjectService.MemberInput> inputs = new ArrayList<>();
            for (MemberSpec spec : request.members()) {
                if (spec == null || spec.userId() == null) {
                    continue;
                }
                inputs.add(new ProjectService.MemberInput(spec.userId(), null, spec.roleCodes()));
            }
            ProjectService.BatchMembersResult result = projects.addMembers(projectId, inputs);
            memberCount = result.added();
            for (ProjectService.MemberFailure failure : result.failures()) {
                warnings.add("成员 " + failure.userId() + " 未加入：" + failure.reason());
            }
        }

        // 3) 用户组授权：与组授权端点同口径（仅管理员）
        int groupCount = 0;
        if (hasGroups) {
            for (GroupSpec spec : request.groups()) {
                if (spec == null || spec.groupId() == null) {
                    continue;
                }
                groups.grant(projectId, spec.groupId(),
                        spec.roleCode() == null || spec.roleCode().isBlank()
                                ? "viewer" : spec.roleCode());
                groupCount += 1;
            }
        }

        // 4) 内容与资产：复用既有 applyContent（它自己带事务，这里加入外层事务）
        int caseCount = 0;
        int assetCount = 0;
        boolean hasCases = (request.caseIds() != null && !request.caseIds().isEmpty())
                || (request.caseSetIds() != null && !request.caseSetIds().isEmpty());
        boolean hasAssets = request.assets() != null && !request.assets().isEmpty();
        boolean hasScenarios = request.scenarioIds() != null && !request.scenarioIds().isEmpty();
        if (hasCases || hasAssets || hasScenarios) {
            List<ProjectTemplateService.AssetRef> refs = new ArrayList<>();
            if (hasAssets) {
                for (AssetSpec spec : request.assets()) {
                    if (spec == null || spec.assetId() == null) {
                        continue;
                    }
                    refs.add(new ProjectTemplateService.AssetRef(
                            spec.assetType() == null || spec.assetType().isBlank()
                                    ? "DEVICE" : spec.assetType(), spec.assetId()));
                }
            }
            ProjectTemplateService.ContentResult content = templates.applyContent(projectId,
                    new ProjectTemplateService.ContentRequest(request.caseIds(), refs,
                            request.setName(), request.caseSetIds(), request.scenarioIds()));
            caseCount = content.cases();
            assetCount = content.assets();
        }

        // 5) 凭证绑定：平台设备两列，无全局权限时降级为警告（见类注释）
        if (hasAssets) {
            for (AssetSpec spec : request.assets()) {
                if (spec == null || spec.assetId() == null) {
                    continue;
                }
                if (spec.managementCredentialId() == null && spec.bmcCredentialId() == null) {
                    continue;
                }
                String type = spec.assetType() == null || spec.assetType().isBlank()
                        ? "DEVICE" : spec.assetType();
                if (!"DEVICE".equalsIgnoreCase(type)) {
                    continue;
                }
                try {
                    assets.bindDeviceCredentials(spec.assetId(), spec.managementCredentialId(),
                            spec.bmcCredentialId());
                } catch (BusinessException e) {
                    warnings.add("设备 " + spec.assetId() + " 凭证未绑定：" + e.getMessage());
                }
            }
        }

        return new CompositeResult(projectId, project.code(), memberCount, groupCount,
                assetCount, caseCount, warnings);
    }
}
