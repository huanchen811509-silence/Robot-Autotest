package com.qa.autotest.application.asset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.EnvProfileRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.asset.EnvProfileSummary;
import com.qa.autotest.domain.asset.EnvSnapshot;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 环境画像与快照（AST-02）：快照可 diff 出变化项。 */
@Service
public class EnvProfileService {

    /** 快照差异的一行。 */
    public record DiffItem(String path, String change, String before, String after) {
    }

    private final EnvProfileRepository profiles;
    private final AccessControlService access;
    private final AuditService audit;
    private final ObjectMapper json;

    public EnvProfileService(EnvProfileRepository profiles, AccessControlService access,
                             AuditService audit, ObjectMapper json) {
        this.profiles = profiles;
        this.access = access;
        this.audit = audit;
        this.json = json;
    }

    public long snapshot(long projectId, String name, String profileType, String description,
                         String snapshotJson) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.CREATE);
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "环境名称不能为空");
        }
        try {
            json.readTree(snapshotJson == null ? "{}" : snapshotJson);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "快照内容不是合法 JSON");
        }
        long id = profiles.insertSnapshot(projectId, name, profileType, description,
                snapshotJson == null ? "{}" : snapshotJson, TenantContext.userId());
        audit.record("env.snapshot", "env_profile", String.valueOf(id), "name=" + name);
        return id;
    }

    public List<EnvSnapshot> list(long projectId, String name) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.READ);
        if (name == null || name.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "环境名称不能为空");
        }
        return profiles.listSnapshots(projectId, name);
    }

    /** 环境名清单（FR-v3.11-02）：按最近快照时间倒序，供总览/准备/拓扑页选择环境。 */
    public List<EnvProfileSummary> environments(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.READ);
        return profiles.listEnvironments(projectId);
    }

    public EnvSnapshot latest(long projectId, String name) {
        access.bindProject(projectId);
        access.require(PermissionResource.ASSET, PermissionAction.READ);
        return profiles.latestSnapshot(projectId, name)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "暂无快照"));
    }

    /** 比较同一环境的两份快照；返回新增 / 删除 / 变更三项。 */
    public List<DiffItem> diff(EnvSnapshot before, EnvSnapshot after) {
        Map<String, String> left = flatten(before.snapshotJson());
        Map<String, String> right = flatten(after.snapshotJson());
        List<DiffItem> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : right.entrySet()) {
            if (!left.containsKey(entry.getKey())) {
                result.add(new DiffItem(entry.getKey(), "added", null, entry.getValue()));
            } else if (!left.get(entry.getKey()).equals(entry.getValue())) {
                result.add(new DiffItem(entry.getKey(), "changed",
                        left.get(entry.getKey()), entry.getValue()));
            }
        }
        for (Map.Entry<String, String> entry : left.entrySet()) {
            if (!right.containsKey(entry.getKey())) {
                result.add(new DiffItem(entry.getKey(), "removed", entry.getValue(), null));
            }
        }
        return result;
    }

    private Map<String, String> flatten(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            JsonNode root = json.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            flattenNode("", root, result);
        } catch (Exception ex) {
            result.put("", raw);
        }
        return result;
    }

    private void flattenNode(String prefix, JsonNode node, Map<String, String> target) {
        if (node == null || node.isNull()) {
            target.put(prefix, "null");
            return;
        }
        if (node.isValueNode()) {
            target.put(prefix, node.asText());
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                flattenNode(path, field.getValue(), target);
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                flattenNode(prefix + "[" + i + "]", node.get(i), target);
            }
            return;
        }
        target.put(prefix, node.toString());
    }
}
