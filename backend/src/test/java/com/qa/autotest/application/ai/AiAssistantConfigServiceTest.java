package com.qa.autotest.application.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.AiAssistantConfigRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.security.SecretCipher;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AI 助手配置服务（v2.5 / FR-AI-10..13）。
 *
 * <p>锁四件事：
 * ① 密钥只以密文入库、只以掩码回显（接口里绝不能出现明文）；
 * ② 「没填密钥」与「清空密钥」是两种语义，前者不能把已有 key 抹掉；
 * ③ 配置要真的变成子进程的 env / 参数，不能只存不用；
 * ④ 对不接受注入的 CLI（Hermes）必须返回空，不许假装生效。
 */
class AiAssistantConfigServiceTest {

    /** 32 字节全零密钥：仅测试用，真实密钥来自 CRED_MASTER_KEY。 */
    private static final String TEST_MASTER = Base64.getEncoder().encodeToString(new byte[32]);

    private FakeRepository repository;
    private AiAssistantConfigService service;

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();
        AiProcessManager processes = new AiProcessManager();
        service = new AiAssistantConfigService(repository,
                SecretCipher.fromBase64Key(TEST_MASTER), processes,
                new AuditService(new NoopAuditRepository(), Runnable::run));
    }

    @Test
    void savedApiKeyIsEncryptedAtRestAndOnlyReturnedAsMask() {
        service.save("pi", "https://ark.example/v3", "deepseek-v4-flash",
                "sk-very-secret-1234", false, true, 9L);

        AiAssistantConfigRepository.Row stored = repository.rows.get("pi");
        assertThat(stored.apiKeyEnc()).doesNotContain("sk-very-secret");
        assertThat(stored.apiKeyEnc()).isNotBlank();

        Map<String, Object> item = find("pi");
        assertThat(item.get("hasKey")).isEqualTo(true);
        assertThat(item.get("keyMasked")).isEqualTo("****1234");
        // 整个返回体里都不许出现明文片段
        assertThat(item.values().toString()).doesNotContain("very-secret");
    }

    @Test
    void blankApiKeyKeepsTheExistingSecretAndClearKeyWipesIt() {
        service.save("dsh", "", "", "sk-first-key", false, true, 9L);
        String first = repository.rows.get("dsh").apiKeyEnc();

        // 只改模型、密钥输入框留空 → 保留原密文，且不进入「覆盖密钥」分支
        service.save("dsh", "", "deepseek-chat", null, false, true, 9L);
        assertThat(repository.lastReplaceKey).isFalse();
        assertThat(repository.rows.get("dsh").apiKeyEnc()).isEqualTo(first);

        // 明确清空 → 走覆盖分支且密文置空
        service.save("dsh", "", "deepseek-chat", null, true, true, 9L);
        assertThat(repository.lastReplaceKey).isTrue();
        assertThat(repository.rows.get("dsh").apiKeyEnc()).isNull();
        assertThat(find("dsh").get("hasKey")).isEqualTo(false);
    }

    @Test
    void configurationBecomesChildProcessEnvAndArgs() {
        service.save("pi", "https://ark.cn-beijing.volces.com/api/coding/v3",
                "deepseek-v4-flash", "sk-abc9999", false, true, 9L);

        Map<String, String> env = service.envFor("pi");
        assertThat(env).containsEntry("OPENAI_BASE_URL",
                "https://ark.cn-beijing.volces.com/api/coding/v3");
        assertThat(env).containsEntry("OPENAI_API_KEY", "sk-abc9999");
        // pi 的 ark provider 读 ARK_API_KEY：同一个 key 要同时喂给两个变量名
        assertThat(env).containsEntry("ARK_API_KEY", "sk-abc9999");
        assertThat(service.modelArgs("pi")).containsExactly("--model", "deepseek-v4-flash");
    }

    @Test
    void aiderUsesItsOwnBaseUrlVariableName() {
        service.save("aider", "https://ark.example/v3", "gpt-5", "sk-aider1", false, true, 9L);
        assertThat(service.envFor("aider")).containsEntry("OPENAI_API_BASE",
                "https://ark.example/v3");
    }

    @Test
    void cliThatCannotBeInjectedStaysEmpty() {
        service.save("hermes", "https://ignored.example", "any-model", "sk-ignored", false,
                true, 9L);
        assertThat(service.envFor("hermes")).isEmpty();
        assertThat(service.modelArgs("hermes")).isEmpty();
    }

    @Test
    void everyCatalogEntryIsListedWithInstallationState() {
        List<Map<String, Object>> items = service.list();
        assertThat(items).hasSize(AiAssistantCatalog.ENTRIES.size());
        assertThat(items).allSatisfy(item -> {
            assertThat(item.get("key")).isNotNull();
            assertThat(item.get("installed")).isInstanceOf(Boolean.class);
            assertThat(item).containsKeys("injectable", "models", "hint", "keyMasked");
        });
    }

    @Test
    void unknownAssistantIsRejected() {
        assertThatThrownBy(() -> service.save("nope", "", "", "", false, true, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未知的 AI 助手");
    }

    private Map<String, Object> find(String key) {
        for (Map<String, Object> item : service.list()) {
            if (key.equals(item.get("key"))) {
                return item;
            }
        }
        throw new AssertionError("列表里没有 " + key);
    }

    /** 内存仓储：只实现被断言的语义，不连库。 */
    private static final class FakeRepository implements AiAssistantConfigRepository {
        private final Map<String, Row> rows = new LinkedHashMap<>();
        private boolean lastReplaceKey;
        private Long lastUserId;

        @Override
        public Map<String, Row> findAll() {
            return rows;
        }

        @Override
        public void save(Row row, boolean replaceKey, Long userId) {
            lastReplaceKey = replaceKey;
            lastUserId = userId;
            Row existing = rows.get(row.cliKey());
            String key = replaceKey || existing == null ? row.apiKeyEnc() : existing.apiKeyEnc();
            rows.put(row.cliKey(), new Row(row.cliKey(), row.baseUrl(), key, row.model(),
                    row.enabled(), "2026-11-08 12:00:00"));
        }
    }
}
