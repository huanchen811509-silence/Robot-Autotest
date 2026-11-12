package com.qa.autotest.application.execution;

import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.common.BusinessException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规模化保护（FR-v5.4-06 / D15–D16 / W54-07）。
 *
 * <p>三件事分开验：
 * <ol>
 *   <li><b>超限必须拒绝</b>，且消息里带上限与当前值——只说「超限了」的报错等于没报；</li>
 *   <li><b>配置错一律回默认</b>，不能让 {@code 0} 或 {@code abc} 变成「全部拒绝」或「等于无上限」；</li>
 *   <li><b>证据配额只算不删</b>，算不出来就不拦——配额是保护措施，不该成为新的单点故障。</li>
 * </ol>
 */
class ScaleGuardTest {

    private static ScaleGuard guard(Map<String, String> values) {
        return new ScaleGuard(new FakeSettings(values));
    }

    @Test
    void 未超上限时放行() {
        assertDoesNotThrow(() -> guard(Map.of()).requireNodes(1L, 8));
        assertDoesNotThrow(() -> guard(Map.of()).requireNodes(1L, ScaleGuard.DEFAULT_MAX_NODES));
    }

    @Test
    void 超过默认上限被拒且说清两个数() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> guard(Map.of()).requireNodes(1L, ScaleGuard.DEFAULT_MAX_NODES + 1));
        assertTrue(ex.getMessage().contains(String.valueOf(ScaleGuard.DEFAULT_MAX_NODES + 1)),
                "消息里要有当前值：" + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(ScaleGuard.DEFAULT_MAX_NODES)),
                "消息里要有上限：" + ex.getMessage());
        assertTrue(ex.getMessage().contains(ScaleGuard.KEY_MAX_NODES),
                "要告诉人改哪个配置项：" + ex.getMessage());
    }

    @Test
    void 项目可调高上限() {
        ScaleGuard wide = guard(Map.of("max_nodes_per_run", "64"));
        assertEquals(64, wide.maxNodes(1L));
        assertDoesNotThrow(() -> wide.requireNodes(1L, 48));
        assertThrows(BusinessException.class, () -> wide.requireNodes(1L, 65));
    }

    @Test
    void 上限配错回默认值() {
        // 0 / 负数 / 非数字 / 超范围都不是「有意义的配置」，生效后果都比重回默认更糟
        assertEquals(ScaleGuard.DEFAULT_MAX_NODES, guard(Map.of("max_nodes_per_run", "0")).maxNodes(1L));
        assertEquals(ScaleGuard.DEFAULT_MAX_NODES, guard(Map.of("max_nodes_per_run", "-3")).maxNodes(1L));
        assertEquals(ScaleGuard.DEFAULT_MAX_NODES, guard(Map.of("max_nodes_per_run", "abc")).maxNodes(1L));
        assertEquals(ScaleGuard.DEFAULT_MAX_NODES, guard(Map.of("max_nodes_per_run", "100000")).maxNodes(1L));
    }

    @Test
    void 配额默认两GB且配错回默认() {
        assertEquals(ScaleGuard.DEFAULT_EVIDENCE_QUOTA_MB, guard(Map.of()).evidenceQuotaMb(1L));
        assertEquals(ScaleGuard.DEFAULT_EVIDENCE_QUOTA_MB,
                guard(Map.of("evidence_quota_mb", "1")).evidenceQuotaMb(1L), "低于 64MB 的下限回默认");
        assertEquals(512, guard(Map.of("evidence_quota_mb", "512")).evidenceQuotaMb(1L));
    }

    @Test
    void 仓库读不到时回默认而不是抛错() {
        ScaleGuard broken = new ScaleGuard(new ProjectSettingRepository() {
            @Override
            public Optional<String> find(long projectId, String key) {
                throw new IllegalStateException("DB down");
            }

            @Override
            public Map<String, String> findAll(long projectId, String prefix) {
                throw new IllegalStateException("DB down");
            }

            @Override
            public void put(long projectId, String key, String value, Long operatorId) {
            }

            @Override
            public void remove(long projectId, String key) {
            }
        });
        assertEquals(ScaleGuard.DEFAULT_MAX_NODES, broken.maxNodes(1L),
                "读配置失败不该让所有执行都失败，用默认值继续");
        assertDoesNotThrow(() -> broken.requireNodes(1L, 4));
    }

    @Test
    void 证据目录未超配额返回空(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("run.log"), "x".repeat(1024), StandardCharsets.UTF_8);
        assertNull(guard(Map.of("evidence_quota_mb", "64")).evidenceOverQuota(1L, dir));
    }

    @Test
    void 证据目录超配额返回说明且不删任何文件(@TempDir Path dir) throws IOException {
        Path big = dir.resolve("a.log");
        // 用稀疏文件造 65MB：配额下限是 64MB，低于它的配置会被当成配错而回落默认
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(big.toFile(), "rw")) {
            raf.setLength(65L * 1024 * 1024);
        }
        String note = guard(Map.of("evidence_quota_mb", "64")).evidenceOverQuota(1L, dir);
        assertNotNull(note, "超配额要给说明");
        assertTrue(note.contains("配额"), note);
        assertTrue(Files.isRegularFile(big), "只读统计，绝不能顺手删证据");
    }

    @Test
    void 目录不存在不算超配额() {
        assertNull(guard(Map.of()).evidenceOverQuota(1L, Path.of("/nonexistent-dir-for-scale-guard")));
        assertNull(guard(Map.of()).evidenceOverQuota(1L, null));
    }

    @Test
    void 命令上限与监视器同一口径() {
        assertEquals(com.qa.autotest.application.execution.monitor.ExecutionMonitor
                .DEFAULT_MAX_COMMANDS, guard(Map.of()).maxCommands());
    }

    /** 只读配置的假仓库；前缀去掉后即为键名（与真实实现同口径）。 */
    private static final class FakeSettings implements ProjectSettingRepository {

        private final Map<String, String> values;

        FakeSettings(Map<String, String> values) {
            this.values = Map.copyOf(values);
        }

        @Override
        public Optional<String> find(long projectId, String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Map<String, String> findAll(long projectId, String prefix) {
            return values;
        }

        @Override
        public void put(long projectId, String key, String value, Long operatorId) {
            // 只读
        }

        @Override
        public void remove(long projectId, String key) {
            // 只读
        }
    }
}
