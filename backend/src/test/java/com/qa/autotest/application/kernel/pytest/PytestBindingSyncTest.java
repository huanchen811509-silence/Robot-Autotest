package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 绑定同步与覆盖率双口径（FR-v5.1-04/05 / 验收 A8–A12 / v5.1.2 / W51-11）。
 *
 * <p>两条最容易做错的判据：
 * <ol>
 *   <li><b>恒等式</b> {@code bound + unbound == total}——破了这条，覆盖率就是编的；</li>
 *   <li><b>重命名跟随</b>——文件名（或声明里的编号）改一下，全量重扫会同时产出一条
 *       unbound 与一条 orphan。两个数看起来都"正常"，实际是同一次改名被记成了两次事故。
 *       这里用「内容哈希不变、路径变」复现，断言改名后不产生假 MISSING。</li>
 * </ol>
 */
class PytestBindingSyncTest {

    private static final long PROJECT = 7L;

    @TempDir
    Path repo;

    private ProjectSettingRepository settings;
    private TestCaseRepository cases;
    private CaseScriptBindingRepository bindings;
    private final Map<Long, CaseScriptBinding> stored = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        settings = mock(ProjectSettingRepository.class);
        cases = mock(TestCaseRepository.class);
        bindings = mock(CaseScriptBindingRepository.class);
        stored.clear();

        when(settings.findAll(PROJECT, PytestProjectSettings.PREFIX))
                .thenReturn(Map.of("repo_path", repo.toString()));
        // 编号 → 优先级：待评审 1 条，用来验证双口径分母
        when(cases.casePriorities(PROJECT)).thenReturn(new LinkedHashMap<>(Map.of(
                "IM-NET-131", "P0", "IM-FUN-020", "P1", "XCCL-001", "待评审")));
        when(cases.listCaseCodes(PROJECT))
                .thenReturn(List.of("IM-NET-131", "IM-FUN-020", "XCCL-001"));
        Map<Long, String> idToCode = new LinkedHashMap<>();
        idToCode.put(1L, "IM-NET-131");
        idToCode.put(2L, "IM-FUN-020");
        idToCode.put(3L, "XCCL-001");
        when(cases.idToCode(PROJECT)).thenReturn(idToCode);

        when(bindings.listByProject(eq(PROJECT), anyString())).thenAnswer(inv ->
                new ArrayList<>(stored.values()));
        when(bindings.deleteByProjectAndKernel(PROJECT, "pytest")).thenAnswer(inv -> {
            int size = stored.size();
            stored.clear();
            return size;
        });
        when(bindings.insert(any(CaseScriptBinding.class), any())).thenAnswer(inv -> {
            CaseScriptBinding binding = inv.getArgument(0);
            long id = stored.size() + 1L;
            stored.put(binding.caseId(), binding);
            return id;
        });
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(repo)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void 恒等式成立且未绑定数是余数() {
        writeScript("tests/nic/test_im_net_131.py", "IM-NET-131");
        PytestBindingSyncService.SyncReport report = sync();

        assertEquals(3, report.totalCases());
        assertEquals(1, report.bound());
        assertEquals(2, report.unbound());
        assertTrue(report.balanced());
        assertEquals(3, report.totalCases(), "bound + unbound 必须等于总数");
    }

    @Test
    void 双口径并列且排除待评审后覆盖率更低() {
        writeScript("tests/nic/test_im_net_131.py", "IM-NET-131");
        PytestBindingSyncService.SyncReport report = sync();

        assertEquals(1, report.pendingCases());
        assertEquals(2, report.totalExcludingPending());
        assertEquals(1, report.boundExcludingPending());
        assertEquals(33.3, report.coveragePercent(), 0.05);
        assertEquals(50.0, report.coverageExcludingPendingPercent(), 0.05);
        // 口径 B 的分母更小，所以这条样本里反而更高；关键是两个数都存在且各自可推
        assertTrue(report.totalExcludingPending() < report.totalCases());
    }

    @Test
    void 文件名改错后再同步不出假_MISSING() throws IOException {
        // 文件名降级来源（无 docstring 声明）才是重命名跟随的真实场景：
        // docstring 声明的编号不随文件名变，改名本来就仍能精确命中。
        Path original = writeFilenameOnlyScript("tests/nic/test_im_net_131.py");
        PytestBindingSyncService.SyncReport first = sync();
        assertEquals(1, first.bound());
        assertEquals(0, first.renamedScripts());
        assertEquals("FILENAME", stored.get(1L).resolveSource());
        String sha = stored.get(1L).scriptSha256();
        assertNotNull(sha);

        // 把文件名里的编号改错（内容一字不动）：编号解析结果变成库里没有的 IM-NET-132
        Path renamed = repo.resolve("tests/nic/test_im_net_132.py");
        Files.move(original, renamed);

        PytestBindingSyncService.SyncReport second = sync();
        assertEquals(1, second.renamedScripts(), "内容哈希一致，应识别为重命名");
        assertEquals(0, second.orphanScripts(), "改名不是孤儿脚本：" + second.orphanCodes());
        assertFalse(second.unboundCodes().contains("IM-NET-131"),
                "改名后不得把原用例记成未绑定：" + second.unboundCodes());
        assertEquals(1, second.bound(), "绑定应跟到新路径");
        assertEquals("tests/nic/test_im_net_132.py", stored.get(1L).scriptPath());
        assertEquals(sha, stored.get(1L).scriptSha256(), "内容没变，哈希必然一致");
        assertTrue(second.renamedPairs().get(0).contains("IM-NET-131"));
    }

    @Test
    void 内容也变了就不跟_照实报孤儿与未绑定() throws IOException {
        Path original = writeFilenameOnlyScript("tests/nic/test_im_net_131.py");
        sync();
        Files.delete(original);
        // 内容必须与旧脚本不同，否则哈希一致会被正当地认成「同一条脚本改了路径」
        write("tests/nic/test_im_net_999.py", """
                # -*- coding: utf-8 -*-
                TARGET_SLOT = "dut1"
                EXTRA_MARKER = "content-changed"


                def test_placeholder():
                    assert True
                """);

        PytestBindingSyncService.SyncReport report = sync();
        assertEquals(0, report.renamedScripts(), "内容变了就不是同一条脚本");
        assertEquals(1, report.orphanScripts());
        assertTrue(report.unboundCodes().contains("IM-NET-131"));
    }

    @Test
    void 一个编号两个脚本判重复但只算一次绑定() {
        writeScript("tests/a/test_im_net_131.py", "IM-NET-131");
        writeScript("tests/b/test_im_net_131.py", "IM-NET-131");

        PytestBindingSyncService.SyncReport report = sync();
        assertEquals(1, report.duplicateBindings());
        assertEquals(1, report.bound(), "重复不叠加分子");
        // 另外两条用例本来就没有脚本，仍如实计入未绑定
        assertEquals(2, report.unbound());
        assertFalse(report.unboundCodes().contains("IM-NET-131"), "有脚本就不算未绑定");
        assertTrue(report.balanced());
    }

    @Test
    void 未配置仓库路径时显式报错而不是静默零绑定() {
        when(settings.findAll(PROJECT, PytestProjectSettings.PREFIX)).thenReturn(Map.of());
        PytestBindingSyncService service =
                new PytestBindingSyncService(settings, cases, bindings);
        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> service.sync(PROJECT, null));
        assertTrue(error.getMessage().contains("repo_path"));
    }

    @Test
    void 写入的绑定带内容哈希与来源() {
        writeScript("tests/nic/test_im_net_020.py", "IM-FUN-020");
        sync();
        ArgumentCaptor<CaseScriptBinding> captor =
                ArgumentCaptor.forClass(CaseScriptBinding.class);
        org.mockito.Mockito.verify(bindings).insert(captor.capture(), any());
        CaseScriptBinding saved = captor.getValue();
        assertEquals("pytest", saved.kernel());
        assertEquals(2L, saved.caseId());
        assertNotNull(saved.scriptSha256());
        assertEquals("DOCSTRING", saved.resolveSource());
        assertFalse(saved.verified(), "自动解析出来的绑定未经人工确认");
    }

    private PytestBindingSyncService.SyncReport sync() {
        return new PytestBindingSyncService(settings, cases, bindings).sync(PROJECT, null);
    }

    /** 写一个带 `用例编号:` 声明的脚本（DOCSTRING 来源，最可信）。 */
    private Path writeScript(String relative, String code) {
        return write(relative, """
                # -*- coding: utf-8 -*-
                用例编号: %s
                TARGET_SLOT = "dut0"


                def test_placeholder():
                    assert True
                """.formatted(code));
    }

    /**
     * 写一个**只有文件名**能推出编号的脚本（无 {@code 用例编号:} 声明）。
     * 这是重命名跟随的适用面：编号寄生在文件名上，改名就会改编号。
     */
    private Path writeFilenameOnlyScript(String relative) {
        return write(relative, """
                # -*- coding: utf-8 -*-
                TARGET_SLOT = "dut0"


                def test_placeholder():
                    assert True
                """);
    }

    private Path write(String relative, String content) {
        Path file = repo.resolve(relative);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return file;
    }
}
