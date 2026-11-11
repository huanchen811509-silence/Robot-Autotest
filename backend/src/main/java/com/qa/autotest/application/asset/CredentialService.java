package com.qa.autotest.application.asset;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.Audited;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectAssetRepository;
import com.qa.autotest.application.repository.CredentialRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.SecretCipher;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.asset.Credential;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 凭据存储（AST-03）：明文只在「写入」与「执行域取用」两个瞬间存在。
 *
 * <p>列表接口只回 {@code configured} 与元信息，任何情况下都不回明文 / 密文。
 */
@Service
public class CredentialService {

    private final CredentialRepository credentials;
    private final SecretCipher cipher;
    private final ProjectAssetRepository projectAssets;
    private final AccessControlService access;
    private final AuditService audit;

    public CredentialService(CredentialRepository credentials, SecretCipher cipher,
                             ProjectAssetRepository projectAssets,
                             AccessControlService access, AuditService audit) {
        this.credentials = credentials;
        this.cipher = cipher;
        this.projectAssets = projectAssets;
        this.access = access;
        this.audit = audit;
    }

    public List<Credential> list(long projectId) {
        access.bindProject(projectId);
        access.require(PermissionResource.CREDENTIAL, PermissionAction.READ);
        return credentials.list(projectId);
    }

    @Audited(action = "credential:upsert", resource = "credential")
    public Credential create(long projectId, String name, String credType, String username,
                             String secret) {
        access.bindProject(projectId);
        access.require(PermissionResource.CREDENTIAL, PermissionAction.CREATE);
        requireText(name, "凭据名称");
        if (credentials.nameExists(projectId, name, null)) {
            throw new BusinessException(ErrorCode.CONFLICT, "凭据名称已存在：" + name);
        }
        long id = credentials.insert(projectId, name, credType, username,
                cipher.encrypt(requireText(secret, "凭据内容")), 1, TenantContext.userId());
        // 只记「已配置」与目标账号，绝不记 secret / 密文
        audit.recordChange("credential:upsert", "credential", String.valueOf(id), null,
                "name=" + name + "; target=" + target(username) + "; configured=true");
        return credentials.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "凭据不存在"));
    }

    /** 更新元信息；{@code secret} 为空表示不轮换密钥内容。 */
    @Audited(action = "credential:upsert", resource = "credential")
    public Credential update(long projectId, long id, String name, String username, String secret) {
        access.bindProject(projectId);
        access.require(PermissionResource.CREDENTIAL, PermissionAction.UPDATE);
        Credential existing = credentials.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "凭据不存在"));
        requireOwned(existing, projectId);
        requireText(name, "凭据名称");
        if (credentials.nameExists(projectId, name, id)) {
            throw new BusinessException(ErrorCode.CONFLICT, "凭据名称已存在：" + name);
        }
        String blob = secret == null || secret.isBlank()
                ? credentials.cipherOf(projectId, id).orElse(null)
                : cipher.encrypt(secret);
        credentials.update(projectId, id, name, username, blob, TenantContext.userId());
        boolean rotate = secret != null && !secret.isBlank();
        audit.recordChange("credential:upsert", "credential", String.valueOf(id),
                "name=" + existing.name() + "; target=" + target(existing.username()),
                "name=" + name + "; target=" + target(username)
                        + "; configured=true; rotated=" + rotate);
        return credentials.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "凭据不存在"));
    }

    public void delete(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CREDENTIAL, PermissionAction.DELETE);
        Credential existing = credentials.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "凭据不存在"));
        // FR-v0.2-51：被引用不可硬删（409），必须先于归属校验，否则平台资产会先落 403
        projectAssets.prepareHardDelete("CREDENTIAL", id);
        requireOwned(existing, projectId);
        credentials.delete(projectId, id);
        audit.record("credential.delete", "credential", String.valueOf(id), "");
    }

    /**
     * 执行域取用明文（不供 HTTP 接口直接调用）。
     *
     * <p>要求 {@code CREDENTIAL:USE} 权限，并落审计；返回值不得写日志。
     */
    public String resolveSecret(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CREDENTIAL, PermissionAction.USE);
        String blob = credentials.cipherOf(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "凭据未配置内容"));
        audit.record("credential.use", "credential", String.valueOf(id), "resolve");
        return cipher.decrypt(blob);
    }

    /**
     * 执行域取用「账号 + 明文」（FR-v4.2-03）：远程主机采集要知道用哪个账号登录。
     *
     * <p>权限与审计口径与 {@link #resolveSecret} 一致（CREDENTIAL:USE + credential.use），
     * 返回值同样不得写进日志。
     */
    public Material resolveMaterial(long projectId, long id) {
        access.bindProject(projectId);
        access.require(PermissionResource.CREDENTIAL, PermissionAction.USE);
        Credential credential = credentials.find(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "凭据不存在"));
        String blob = credentials.cipherOf(projectId, id)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "凭据未配置内容"));
        audit.record("credential.use", "credential", String.valueOf(id), "host-inventory");
        // connectOptions 直接取已加载的实体字段，不再补一次单列查询：
        // 那次多余查询曾在 connect_options 为 NULL 时把整条采集打成 500（v4.12.0 W-D 实测）。
        return new Material(credential.username(), credential.credType(), cipher.decrypt(blob),
                credential.connectOptions());
    }

    /**
     * 执行域材料（账号 + 类型 + 明文 + 连接参数）；{@code toString} 对明文打码，防止顺手打日志泄漏。
     *
     * @param credType       决定采集通道（ssh / network / bmc）
     * @param connectOptions SSH 附加参数（老 KEX 兼容），可为 null
     */
    public record Material(String username, String credType, String secret, String connectOptions) {

        public Material(String username, String credType, String secret) {
            this(username, credType, secret, null);
        }

        @Override
        public String toString() {
            return "Material[username=" + username + ", credType=" + credType
                    + ", connectOptions=" + (connectOptions == null ? "-" : connectOptions)
                    + ", secret=***]";
        }
    }

    /** 平台共享凭据对引用方只读：可被取用执行，但元信息与密文只能由归属项目改。 */
    private static void requireOwned(Credential credential, long projectId) {
        if (credential.projectId() != projectId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "平台共享资产只能由归属项目修改");
        }
    }

    /** 目标账号为空时写「未指定」，避免摘要出现 null。 */
    private static String target(String username) {
        return username == null || username.isBlank() ? "未指定" : username;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, field + "不能为空");
        }
        return value;
    }
}
