package com.qa.autotest.application.ai;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.kernel.EnvironmentProbe;
import com.qa.autotest.application.repository.AiAssistantConfigRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.security.SecretCipher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * AI 助手配置（v2.5 / FR-AI-10..13）。
 *
 * <p>三件事必须同时成立，否则这个页面就是摆设：
 * <ol>
 *   <li><b>存得住</b>：URL / 模型明文存、密钥密文存（{@link SecretCipher}）；</li>
 *   <li><b>回得去但不泄露</b>：接口只回掩码（{@code ****} + 尾 4 位），永不回明文；</li>
 *   <li><b>真的生效</b>：{@link #envFor(String)} 与 {@link #modelArgs(String)} 把配置
 *       注入真实子进程，不在页面和运行时之间留一条断头路。</li>
 * </ol>
 *
 * <p>对不接受注入的 CLI（Hermes 走自己的配置文件），条目里三个注入字段都是 null，
 * 页面显示「仅记录」，这里也不会假装注入成功。
 */
@Service
public class AiAssistantConfigService {

    private static final String MASK_PREFIX = "****";
    private static final int MASK_TAIL = 4;

    private final AiAssistantConfigRepository repository;
    private final SecretCipher cipher;
    private final AiProcessManager processes;
    private final AuditService audit;

    public AiAssistantConfigService(AiAssistantConfigRepository repository, SecretCipher cipher,
                                    AiProcessManager processes, AuditService audit) {
        this.repository = repository;
        this.cipher = cipher;
        this.processes = processes;
        this.audit = audit;
    }

    /** 设置页主列表：目录 × 落库配置 × 本机安装状态。 */
    public List<Map<String, Object>> list() {
        Map<String, AiAssistantConfigRepository.Row> rows = repository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (AiAssistantCatalog.Entry entry : AiAssistantCatalog.ENTRIES) {
            AiAssistantConfigRepository.Row row = rows.get(entry.key());
            String plainKey = row == null ? null : decryptQuietly(row.apiKeyEnc());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", entry.key());
            item.put("name", entry.name());
            item.put("command", entry.command());
            item.put("installed", installed(entry.key()));
            item.put("injectable", entry.injectable());
            item.put("credentialEnv",
                    entry.credentialEnv().isEmpty() ? null : entry.credentialEnv().get(0));
            item.put("credentialEnvs", entry.credentialEnv());
            item.put("baseUrlEnv", entry.baseUrlEnv());
            item.put("modelFlag", entry.modelFlag());
            item.put("hint", entry.credentialHint());
            item.put("models", entry.models());
            item.put("baseUrl", row == null ? "" : nullToEmpty(row.baseUrl()));
            item.put("model", row == null ? "" : nullToEmpty(row.model()));
            item.put("enabled", row == null || row.enabled());
            item.put("hasKey", row != null && row.apiKeyEnc() != null
                    && !row.apiKeyEnc().isBlank());
            item.put("keyMasked", plainKey == null || plainKey.isBlank() ? "" : mask(plainKey));
            // 有密文但解不开（密钥轮换 / 密文损坏）：显式标出来，别让它看起来像「没配」
            item.put("keyUnreadable", row != null && row.apiKeyEnc() != null
                    && !row.apiKeyEnc().isBlank() && (plainKey == null || plainKey.isBlank()));
            item.put("updatedAt", row == null ? "" : nullToEmpty(row.updatedAt()));
            result.add(item);
        }
        return result;
    }

    /**
     * 保存单个助手配置。
     *
     * @param apiKey   非空=换成新密钥；null/空=沿用库里已有的（不清空）
     * @param clearKey true=明确清空密钥（与「没填」区分开）
     */
    public Map<String, Object> save(String key, String baseUrl, String model, String apiKey,
                                    boolean clearKey, boolean enabled, Long userId) {
        AiAssistantCatalog.Entry entry = require(key);
        boolean replaceKey = clearKey || (apiKey != null && !apiKey.isBlank());
        String encrypted = null;
        if (apiKey != null && !apiKey.isBlank()) {
            encrypted = cipher.encrypt(apiKey.trim());
        }
        repository.save(new AiAssistantConfigRepository.Row(
                        entry.key(),
                        blankToNull(baseUrl),
                        encrypted,
                        blankToNull(model),
                        enabled,
                        null),
                replaceKey, userId);
        audit.record("AI_ASSISTANT_CONFIG", "ai_assistant_config", entry.key(),
                "enabled=" + enabled
                        + "; baseUrl=" + (blankToNull(baseUrl) == null ? "default" : "custom")
                        + "; model=" + (blankToNull(model) == null ? "default" : model)
                        + "; key=" + (apiKey != null && !apiKey.isBlank() ? "updated"
                        : (clearKey ? "cleared" : "unchanged")));
        return find(list(), entry.key());
    }

    /**
     * 测试连接：用**当前保存的配置**跑一次真实 CLI（固定短提示、受超时约束）。
     *
     * <p>返回里刻意带上 {@code appliedEnv}（变量名，不含值）与 {@code appliedArgs}：
     * 用户能据此判断「我填的端点到底有没有被送进去」，而不是只看一句成功/失败。
     */
    public Map<String, Object> test(String key) {
        AiAssistantCatalog.Entry entry = require(key);
        if (!installed(entry.key())) {
            return result(false, "未安装：PATH 上没有 " + entry.key()
                    + (entry.key().equals("pi") ? " 且内置副本不可用" : ""), 0, -1, "", "");
        }
        Map<String, String> env = envFor(entry.key());
        List<String> args = modelArgs(entry.key());
        long start = System.currentTimeMillis();
        AiProcessResult pr = processes.executeAdminTest(entry.key(),
                "请只回复两个字：已连通", env, args);
        long elapsed = System.currentTimeMillis() - start;

        boolean ok = pr.executed() && !pr.timedOut() && pr.exitCode() == 0
                && !pr.stdout().isBlank();
        Map<String, Object> out = result(ok, ok ? "OK" : pr.reason(), elapsed, pr.exitCode(),
                snippet(pr.stdout()), snippet(pr.stderr()));
        out.put("appliedEnv", new ArrayList<>(env.keySet()));
        out.put("appliedArgs", args);
        audit.record("AI_ASSISTANT_TEST", "ai_assistant_config", entry.key(),
                "ok=" + ok + "; exit=" + pr.exitCode() + "; elapsedMs=" + elapsed);
        return out;
    }

    /** 供真实调用注入：只有配置存在且该 CLI 接受注入时才产出非空 map。 */
    public Map<String, String> envFor(String key) {
        AiAssistantCatalog.Entry entry = AiAssistantCatalog.find(key);
        if (entry == null) {
            return Map.of();
        }
        AiAssistantConfigRepository.Row row = repository.findAll().get(entry.key());
        if (row == null) {
            return Map.of();
        }
        Map<String, String> env = new LinkedHashMap<>();
        if (entry.baseUrlEnv() != null && row.baseUrl() != null && !row.baseUrl().isBlank()) {
            env.put(entry.baseUrlEnv(), row.baseUrl().trim());
        }
        String plainKey = decryptQuietly(row.apiKeyEnc());
        if (plainKey != null && !plainKey.isBlank()) {
            // 多值：同一个 key 可能要通过多个变量名交给 CLI（例如 pi 的 ark provider
            // 读 ARK_API_KEY，默认 openai provider 读 OPENAI_API_KEY）
            for (String name : entry.credentialEnv()) {
                env.put(name, plainKey);
            }
        }
        return env;
    }

    /** 供真实调用注入：模型参数（{@code --model xxx}），不接受模型参数的 CLI 返回空。 */
    public List<String> modelArgs(String key) {
        AiAssistantCatalog.Entry entry = AiAssistantCatalog.find(key);
        if (entry == null || entry.modelFlag() == null) {
            return List.of();
        }
        AiAssistantConfigRepository.Row row = repository.findAll().get(entry.key());
        if (row == null || row.model() == null || row.model().isBlank()) {
            return List.of();
        }
        return List.of(entry.modelFlag(), row.model().trim());
    }

    private static AiAssistantCatalog.Entry require(String key) {
        AiAssistantCatalog.Entry entry = AiAssistantCatalog.find(key);
        if (entry == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "未知的 AI 助手: " + key);
        }
        return entry;
    }

    private static Map<String, Object> result(boolean ok, String reason, long elapsedMs,
                                              int exitCode, String stdout, String stderr) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("reason", reason);
        out.put("elapsedMs", elapsedMs);
        out.put("exitCode", exitCode);
        out.put("stdout", stdout);
        out.put("stderr", stderr);
        return out;
    }

    /** 只取前 400 字符：测试结果用于判断通不通，不需要整段回显。 */
    private static String snippet(String value) {
        if (value == null) {
            return "";
        }
        String text = value.strip();
        return text.length() <= 400 ? text : text.substring(0, 400);
    }

    private static boolean installed(String key) {
        if (EnvironmentProbe.onPath(key)) {
            return true;
        }
        return "pi".equals(key) && AiBundledAgent.available();
    }

    private String decryptQuietly(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            return cipher.decrypt(encrypted);
        } catch (RuntimeException ex) {
            // 密钥轮换 / 密文损坏：不让整页 500，按「有值但读不出」处理（hasKey 仍为 true）
            return null;
        }
    }

    private static String mask(String plain) {
        if (plain.length() <= MASK_TAIL) {
            return MASK_PREFIX;
        }
        return MASK_PREFIX + plain.substring(plain.length() - MASK_TAIL);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Map<String, Object> find(List<Map<String, Object>> items, String key) {
        for (Map<String, Object> item : items) {
            if (key.equals(item.get("key"))) {
                return item;
            }
        }
        return Map.of();
    }
}
