package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.common.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工程配置校验（FR-v5.2-02 / 验收 B4–B6 / W52-02）。
 *
 * <p>这组用例的存在理由：配置项直接进执行命令，而 KV 表**不会替你校验任何东西**。
 * 一个拼错的键、一条相对路径、一个 `extra_args` 里重复的 `--mode`，都会被静默存下、
 * 静默读不到、最后表现为「配了但还是报未配置」或「平台配的模式没生效」。
 *
 * <p>所以判据集中在三处：<b>未知键拒绝</b>（不是忽略）、<b>原子性</b>（一项错则整体不落库）、
 * <b>值形态</b>（绝对/相对、枚举、区间、不许重复装配参数）。
 */
class PytestSettingsServiceTest {

    private static final long PROJECT = 9L;

    private MemorySettings settings;
    private AuditService audit;
    private PytestSettingsService service;

    @BeforeEach
    void setUp() {
        settings = new MemorySettings();
        audit = mock(AuditService.class);
        TestCaseRepository cases = mock(TestCaseRepository.class);
        CaseScriptBindingRepository bindings = mock(CaseScriptBindingRepository.class);
        when(cases.countByProject(anyLong())).thenReturn(0L);
        when(bindings.countByProject(anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(0L);
        when(bindings.listByProject(anyLong(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
        // 真实健康服务：配置里没写 python_bin 时它不会去执行任何进程，
        // 但能给出「未配置解释器」这一条真实结论，比 mock 一个空对象更接近实际。
        PytestHealthService health = new PytestHealthService(settings, new PytestContractProbe(),
                bindings, cases);
        service = new PytestSettingsService(settings, audit, health);
    }

    @Test
    void 未知键直接拒绝而不是默默忽略() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.save(PROJECT, Map.of("python-bin", "/usr/bin/python3")));
        assertTrue(error.getMessage().contains("python-bin"), error.getMessage());
        assertTrue(error.getMessage().contains("已知"), error.getMessage());
        assertTrue(settings.store.isEmpty(), "拒绝时一行都不许落库");
    }

    @Test
    void 一项非法则整体不落库() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("repo_path", "/srv/nic-cases");
        body.put("mode_default", "real");
        body.put("timeout_s", "abc");
        assertThrows(BusinessException.class, () -> service.save(PROJECT, body));
        assertTrue(settings.store.isEmpty(),
                "半保存比保存失败更难查：页面说成功、实际只生效一半");
    }

    @Test
    void 模式只认_fake_与_real() {
        assertThrows(BusinessException.class,
                () -> service.save(PROJECT, Map.of("mode_default", "prod")));
        assertTrue(service.check(Map.of("mode_default", "REAL")).isEmpty(), "大小写不敏感");
        assertTrue(service.check(Map.of("mode_default", "fake")).isEmpty());
    }

    @Test
    void 超时低于三十秒被拒() {
        assertFalse(service.check(Map.of("timeout_s", "5")).isEmpty());
        assertFalse(service.check(Map.of("timeout_s", "0")).isEmpty());
        assertFalse(service.check(Map.of("timeout_s", "-1")).isEmpty());
        assertFalse(service.check(Map.of("timeout_s", "90000")).isEmpty(), "超过 24h 也要拒");
        assertTrue(service.check(Map.of("timeout_s", "600")).isEmpty());
    }

    @Test
    void 仓库与解释器必须是绝对路径_配置路径必须是相对路径() {
        assertFalse(service.check(Map.of("repo_path", "nic-cases")).isEmpty());
        assertFalse(service.check(Map.of("python_bin", "./venv/bin/python")).isEmpty());
        assertTrue(service.check(Map.of("repo_path", "/srv/nic-cases")).isEmpty());
        // 反过来：env_config 是相对仓库根的文件，写成绝对路径换机器即失效
        assertFalse(service.check(Map.of("env_config", "/abs/run_env.yaml")).isEmpty());
        assertTrue(service.check(Map.of("env_config", "config/run_env.yaml")).isEmpty());
    }

    @Test
    void extra_args不许重复装配平台自己拼的参数() {
        // 用户再写一遍 --mode，pytest 取最后一个，表现是「平台配的模式没生效」
        assertFalse(service.check(Map.of("extra_args", "--mode real")).isEmpty());
        assertFalse(service.check(Map.of("extra_args", "--junitxml=/tmp/x.xml")).isEmpty());
        assertFalse(service.check(Map.of("extra_args", "-k foo && rm -rf /")).isEmpty(),
                "参数不经 shell，连接符一定是误写");
        assertTrue(service.check(Map.of("extra_args", "-k nic_131 -x")).isEmpty());
    }

    @Test
    void 空值等于恢复未配置而不是写入空串() {
        settings.put(PROJECT, "pytest.repo_path", "/srv/nic-cases", null);
        service.save(PROJECT, Map.of("repo_path", ""));
        assertFalse(settings.store.containsKey("pytest.repo_path"),
                "空串留在表里会让「未配置」判断全失效");
    }

    @Test
    void 保存结果带上必填缺口与健康快照() {
        PytestSettingsService.SaveResult result = service.save(PROJECT, Map.of(
                "mode_default", "fake",
                "repo_path", "/srv/nic-cases"));
        assertTrue(result.saved());
        assertEquals(List.of("python_bin", "env_config", "inventory_path"),
                result.missingForExecute(), "缺的必填项要按声明顺序列出");
        assertFalse(result.health().healthy(), "没配解释器不可能健康");
        assertTrue(result.health().hints().stream().anyMatch(h -> h.contains("python_bin")),
                "提示要直接给出修复落点：" + result.health().hints());
    }

    @Test
    void 保存成功会写一条审计且不带值() {
        service.save(PROJECT, Map.of("repo_path", "/srv/secret-path/nic-cases"));
        org.mockito.ArgumentCaptor<String> detail = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(audit).recordChange(
                org.mockito.ArgumentMatchers.eq("pytest-setting:update"),
                org.mockito.ArgumentMatchers.eq("project_setting"),
                org.mockito.ArgumentMatchers.eq(String.valueOf(PROJECT)),
                detail.capture(), org.mockito.ArgumentMatchers.isNull());
        assertTrue(detail.getValue().contains("repo_path"), detail.getValue());
        assertFalse(detail.getValue().contains("/srv/secret-path"),
                "审计只记键名不记值：路径里可能带用户名或主机名");
    }

    /** 内存版配置仓储：行为与 JDBC 实现一致（覆盖写 + 空值删除）。 */
    private static final class MemorySettings implements ProjectSettingRepository {
        private final Map<String, String> store = new LinkedHashMap<>();

        @Override
        public Optional<String> find(long projectId, String key) {
            return Optional.ofNullable(store.get(key));
        }

        @Override
        public Map<String, String> findAll(long projectId, String prefix) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : store.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    result.put(entry.getKey().substring(prefix.length()), entry.getValue());
                }
            }
            return result;
        }

        @Override
        public void put(long projectId, String key, String value, Long operatorId) {
            store.put(key, value);
        }

        @Override
        public void remove(long projectId, String key) {
            store.remove(key);
        }
    }
}
