package com.qa.autotest.application.view;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.SavedViewRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 保存视图（FR-3.1-03）。
 *
 * <p>三条硬规则：
 * <ul>
 *   <li>只能读写自己的视图（user_id 取自上下文）；</li>
 *   <li>同名同作用域冲突返回 409；</li>
 *   <li>payload 只接受 JSON 对象且序列化后 ≤ 8KB，避免把整页数据塞进单行。</li>
 * </ul>
 */
@Service
public class SavedViewService {

    public static final int MAX_PAYLOAD_BYTES = 8 * 1024;
    /** 与 saved_view 列定义保持一致：view_scope VARCHAR(24)、name VARCHAR(80)。 */
    private static final int MAX_SCOPE_LENGTH = 24;
    private static final int MAX_NAME_LENGTH = 80;

    public record SaveRequest(String name, String scope, Object payload, boolean isDefault) {
    }

    private final SavedViewRepository repository;
    private final AccessControlService access;
    private final ObjectMapper json;

    public SavedViewService(SavedViewRepository repository, AccessControlService access,
                            ObjectMapper json) {
        this.repository = repository;
        this.access = access;
        this.json = json;
    }

    public List<SavedView> list(long projectId, String scope) {
        access.bindProject(projectId);
        return repository.list(projectId, TenantContext.requireUserId(), normalizeScope(scope));
    }

    public SavedView create(long projectId, SaveRequest request) {
        access.bindProject(projectId);
        long userId = TenantContext.requireUserId();
        String scope = normalizeScope(request.scope());
        String name = validateName(request.name());
        validatePayload(request.payload());
        if (repository.nameExists(projectId, userId, scope, name, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "同名视图已存在");
        }
        SavedView candidate = new SavedView(0, projectId, userId, name, scope,
                request.payload(), request.isDefault(), null, null);
        try {
            long id = repository.insert(candidate);
            long effectiveId = id > 0 ? id : -1;
            if (request.isDefault() && effectiveId > 0) {
                repository.clearDefault(projectId, userId, scope, effectiveId);
            }
            return repository.find(projectId, effectiveId, userId).orElse(candidate);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "同名视图已存在");
        }
    }

    public SavedView update(long projectId, long id, SaveRequest request) {
        access.bindProject(projectId);
        long userId = TenantContext.requireUserId();
        SavedView existing = repository.find(projectId, id, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "视图不存在"));
        String scope = normalizeScope(request.scope());
        String name = validateName(request.name());
        validatePayload(request.payload());
        if (repository.nameExists(projectId, userId, scope, name, id)) {
            throw new BusinessException(ErrorCode.CONFLICT, "同名视图已存在");
        }
        SavedView updated = new SavedView(id, projectId, userId, name, scope,
                request.payload(), request.isDefault(), existing.createdAt(), existing.updatedAt());
        try {
            repository.update(updated);
            if (request.isDefault()) {
                repository.clearDefault(projectId, userId, scope, id);
            }
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "同名视图已存在");
        }
        return repository.find(projectId, id, userId).orElse(updated);
    }

    public boolean delete(long projectId, long id) {
        access.bindProject(projectId);
        long userId = TenantContext.requireUserId();
        if (repository.find(projectId, id, userId).isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "视图不存在");
        }
        repository.delete(projectId, id, userId);
        return true;
    }

    private static String normalizeScope(String scope) {
        String value = scope == null || scope.isBlank() ? "case" : scope.trim().toLowerCase();
        if (value.length() > MAX_SCOPE_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "scope 过长");
        }
        return value;
    }

    private static String validateName(String name) {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "视图名称不能为空");
        }
        if (value.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "视图名称过长");
        }
        return value;
    }

    private void validatePayload(Object payload) {
        if (payload == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "payload 不能为空");
        }
        JsonNode node = json.valueToTree(payload);
        if (!node.isObject()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "payload 必须是 JSON 对象");
        }
        try {
            byte[] bytes = json.writeValueAsBytes(node);
            if (bytes.length > MAX_PAYLOAD_BYTES) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "payload 超过 8KB");
            }
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "payload 必须是合法 JSON");
        }
    }
}
