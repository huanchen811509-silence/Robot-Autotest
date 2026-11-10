package com.qa.autotest.application.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 导出包 manifest 的服务端 HMAC-SHA256 签名与验签（ADR-4X-12）。
 *
 * <p>密钥优先取 {@code app.workspace.signing-key}（环境变量 {@code WORKSPACE_SIGNING_KEY}）；
 * 未注入时读 {@code <workspace.root>/.signing-key}，不存在则生成 32 字节随机十六进制并尽力设为 0600。
 * 取不到密钥时**失败**，不降级为无签名导出。</p>
 */
@Component
public class WorkspaceBundleSigner {

    public static final String ALGORITHM = "HMAC-SHA256";
    public static final String KEY_ID = "server-local-v1";
    static final String KEY_FILE = ".signing-key";

    private final WorkspaceProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile byte[] key;

    public WorkspaceBundleSigner(WorkspaceProperties properties) {
        this.properties = properties;
    }

    /** @param manifestWithoutSignature 已去掉 signature 的清单节点；返回 64 位小写十六进制。 */
    public String sign(JsonNode manifestWithoutSignature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(CanonicalJson.write(manifestWithoutSignature).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException exc) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "工作区签名失败: " + exc.getMessage());
        }
    }

    /** 常量时间比较；签名缺失/格式不符一律 false。 */
    public boolean verify(JsonNode manifestWithoutSignature, String signatureHex) {
        if (signatureHex == null || signatureHex.length() != 64) {
            return false;
        }
        byte[] actual;
        try {
            actual = HexFormat.of().parseHex(signatureHex);
        } catch (IllegalArgumentException exc) {
            return false;
        }
        return MessageDigest.isEqual(HexFormat.of().parseHex(sign(manifestWithoutSignature)), actual);
    }

    public String keyId() {
        return KEY_ID;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    private byte[] key() {
        byte[] current = key;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (key == null) {
                key = loadKey();
            }
            return key;
        }
    }

    private byte[] loadKey() {
        String configured = properties.getSigningKey();
        if (configured != null && !configured.isBlank()) {
            return parseHex(configured.trim(), "app.workspace.signing-key");
        }
        Path root = properties.rootPath();
        Path keyFile = root.resolve(KEY_FILE);
        try {
            Files.createDirectories(root);
            if (!Files.exists(keyFile)) {
                byte[] generated = new byte[32];
                new SecureRandom().nextBytes(generated);
                try {
                    Files.writeString(keyFile, HexFormat.of().formatHex(generated),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                } catch (java.nio.file.FileAlreadyExistsException race) {
                    // 并发首启：以先落盘的为准
                }
                restrictPermissions(keyFile);
            }
            return parseHex(Files.readString(keyFile, StandardCharsets.UTF_8).trim(), keyFile.toString());
        } catch (IOException exc) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "工作区签名密钥不可用（请设置 WORKSPACE_SIGNING_KEY 或确保 " + keyFile + " 可读写）");
        }
    }

    private static byte[] parseHex(String hex, String source) {
        if (hex.length() != 64) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "签名密钥必须是 64 位十六进制（来源: " + source + "）");
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException exc) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "签名密钥不是合法十六进制（来源: " + source + "）");
        }
    }

    private static void restrictPermissions(Path file) {
        try {
            Files.setPosixFilePermissions(file, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows/NTFS 无 POSIX 权限：保留默认 ACL，不因权限设置失败而阻断导出
        }
    }
}
