package com.qa.autotest.application.kernel.pytest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import com.qa.autotest.domain.kernel.CaseStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 结果归一（W51-07 / FR-v5.1-08 / GAP-09）。
 *
 * 核心：junit 的**点分** classname 与绑定表的**斜杠** nodeid 不同源，
 * 匹配必须走 `<文件末段>::<函数名>`，且参数化后缀要剥掉。
 */
class PytestResultNormalizerTest {

    private static final long PROJECT = 5L;

    @Test
    void 点分classname能匹配回库编号(@TempDir Path dir) throws Exception {
        Path junit = writeJunit(dir, """
                <testsuite name="pytest" tests="1">
                  <testcase classname="tests.nic.base.test_im_fun_020"
                            name="test_im_fun_020" time="1.25"/>
                </testsuite>
                """);

        PytestResultNormalizer.NormalizeResult result = normalizer().normalize(PROJECT, "RUN-1",
                List.of(junit), Map.of("QA_PROJECT", "NIC", "DUT", "gpu01", "LINK", "L1",
                        "SW_BUILD", "abc123"), dir);

        assertThat(result.run().cases()).hasSize(1);
        assertThat(result.run().cases().get(0).name()).isEqualTo("IM-FUN-020");
        assertThat(result.run().cases().get(0).status()).isEqualTo(CaseStatus.PASS);
        assertThat(result.unbound()).isZero();
    }

    @Test
    void 参数化后缀被剥掉仍能匹配(@TempDir Path dir) throws Exception {
        Path junit = writeJunit(dir, """
                <testsuite tests="2">
                  <testcase classname="tests.nic.ethernet.test_im_net_131"
                            name="test_im_net_131[forward]" time="2.0"/>
                  <testcase classname="tests.nic.ethernet.test_im_net_131"
                            name="test_im_net_131[reverse]" time="2.1"/>
                </testsuite>
                """);

        PytestResultNormalizer.NormalizeResult result = normalizer().normalize(PROJECT, "RUN-1",
                List.of(junit), Map.of(), dir);

        assertThat(result.run().cases()).hasSize(2);
        assertThat(result.run().cases()).allMatch(c -> c.name().equals("IM-NET-131"));
        assertThat(result.unbound()).isZero();
    }

    @Test
    void 未绑定的用例保留nodeid并计入unbound(@TempDir Path dir) throws Exception {
        Path junit = writeJunit(dir, """
                <testsuite tests="1">
                  <testcase classname="tests.nic.other.test_unknown_999"
                            name="test_unknown_999" time="0.4"/>
                </testsuite>
                """);

        PytestResultNormalizer.NormalizeResult result = normalizer().normalize(PROJECT, "RUN-1",
                List.of(junit), Map.of(), dir);

        assertThat(result.unbound()).isEqualTo(1);
        assertThat(result.run().cases().get(0).name())
                .isEqualTo("tests.nic.other.test_unknown_999::test_unknown_999");
    }

    @Test
    void 全skip时通过率不适用(@TempDir Path dir) throws Exception {
        Path junit = writeJunit(dir, """
                <testsuite tests="1">
                  <testcase classname="tests.nic.base.test_im_fun_020"
                            name="test_im_fun_020" time="0.01">
                    <skipped message="plan only"/>
                  </testcase>
                </testsuite>
                """);

        PytestResultNormalizer.NormalizeResult result = normalizer().normalize(PROJECT, "RUN-1",
                List.of(junit), Map.of(), dir);

        assertThat(result.allSkipped()).isTrue();
        assertThat(result.passRateApplicable()).isFalse();
    }

    @Test
    void 失败与错误分别映射(@TempDir Path dir) throws Exception {
        Path junit = writeJunit(dir, """
                <testsuite tests="2">
                  <testcase classname="tests.nic.base.test_im_fun_020"
                            name="test_im_fun_020" time="1.0">
                    <failure message="assert 1 == 2"/>
                  </testcase>
                  <testcase classname="tests.nic.ethernet.test_im_net_131"
                            name="test_im_net_131" time="0.2">
                    <error message="SSH connect refused"/>
                  </testcase>
                </testsuite>
                """);

        PytestResultNormalizer.NormalizeResult result = normalizer().normalize(PROJECT, "RUN-1",
                List.of(junit), Map.of(), dir);

        assertThat(result.run().cases()).extracting(c -> c.status())
                .containsExactly(CaseStatus.FAIL, CaseStatus.ERROR);
        assertThat(result.run().failures()).isEqualTo(2);
    }

    @Test
    void sidecar落盘且缺项清单可见(@TempDir Path dir) throws Exception {
        Path junit = writeJunit(dir, """
                <testsuite tests="1">
                  <testcase classname="tests.nic.base.test_im_fun_020"
                            name="test_im_fun_020" time="1.0"/>
                </testsuite>
                """);

        PytestResultNormalizer.NormalizeResult result = normalizer().normalize(PROJECT, "RUN-1",
                List.of(junit), Map.of("QA_PROJECT", "NIC"), dir);

        assertThat(result.sidecar()).exists();
        String sidecar = Files.readString(result.sidecar(), StandardCharsets.UTF_8);
        assertThat(sidecar).contains("RUN_ID");
        assertThat(sidecar).contains("missing");
        assertThat(result.run().exportable()).isFalse();
    }

    @Test
    void 多批junit合并计数(@TempDir Path dir) throws Exception {
        Path first = writeJunit(dir.resolve("b0"), """
                <testsuite tests="1">
                  <testcase classname="tests.nic.base.test_im_fun_020"
                            name="test_im_fun_020" time="1.0"/>
                </testsuite>
                """);
        Path second = writeJunit(dir.resolve("b1"), """
                <testsuite tests="1">
                  <testcase classname="tests.nic.ethernet.test_im_net_131"
                            name="test_im_net_131" time="2.0"/>
                </testsuite>
                """);

        PytestResultNormalizer.NormalizeResult result = normalizer().normalize(PROJECT, "RUN-1",
                List.of(first, second), Map.of(), dir);

        assertThat(result.run().total()).isEqualTo(2);
    }

    @Test
    void 匹配键规则固定(@TempDir Path ignored) {
        assertThat(PytestResultNormalizer.matchKey("tests.nic.base.test_im_fun_020",
                "test_im_fun_020")).isEqualTo("test_im_fun_020::test_im_fun_020");
        assertThat(PytestResultNormalizer.matchKey("tests/nic/base/test_im_fun_020.py",
                "test_im_fun_020[case1]")).isEqualTo("test_im_fun_020::test_im_fun_020");
    }

    private static Path writeJunit(Path dir, String body) throws Exception {
        Path file = dir.resolve("junit.xml");
        Files.createDirectories(dir);
        Files.writeString(file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + body,
                StandardCharsets.UTF_8);
        return file;
    }

    private static PytestResultNormalizer normalizer() {
        Map<Long, String> codeById = new LinkedHashMap<>();
        codeById.put(101L, "IM-FUN-020");
        codeById.put(102L, "IM-NET-131");
        List<CaseScriptBinding> bindings = List.of(
                new CaseScriptBinding(1L, PROJECT, 101L, "pytest",
                        "tests/nic/base/test_im_fun_020.py",
                        "tests/nic/base/test_im_fun_020.py::test_im_fun_020", "sha1",
                        "DOCSTRING", true),
                new CaseScriptBinding(2L, PROJECT, 102L, "pytest",
                        "tests/nic/ethernet/test_im_net_131.py",
                        "tests/nic/ethernet/test_im_net_131.py::test_im_net_131", "sha2",
                        "DOCSTRING", true));
        return new PytestResultNormalizer(new FakeBindings(bindings),
                new FakeCases(codeById), new ObjectMapper());
    }

    private record FakeBindings(List<CaseScriptBinding> all)
            implements CaseScriptBindingRepository {

        @Override
        public Optional<CaseScriptBinding> findByCase(long projectId, long caseId, String kernel) {
            return all.stream().filter(b -> b.caseId() == caseId).findFirst();
        }

        @Override
        public Optional<CaseScriptBinding> findByNodeId(long projectId, String kernel,
                String nodeId) {
            return all.stream().filter(b -> nodeId.equals(b.nodeId())).findFirst();
        }

        @Override
        public List<CaseScriptBinding> listByProject(long projectId, String kernel) {
            return all;
        }

        @Override
        public Optional<CaseScriptBinding> findByPath(long projectId, String kernel,
                String scriptPath) {
            return all.stream().filter(b -> scriptPath.equals(b.scriptPath())).findFirst();
        }

        @Override
        public List<CaseScriptBinding> findBySha(long projectId, String kernel, String sha) {
            return List.of();
        }

        @Override
        public int deleteByProjectAndKernel(long projectId, String kernel) {
            return 0;
        }

        @Override
        public long insert(CaseScriptBinding binding, Long operatorId) {
            return 0;
        }

        @Override
        public void update(CaseScriptBinding binding, Long operatorId) {
        }

        @Override
        public void deleteById(long projectId, long id) {
        }

        @Override
        public long countByProject(long projectId, String kernel) {
            return all.size();
        }
    }

    private record FakeCases(Map<Long, String> codeById) implements TestCaseRepository {

        @Override
        public Map<Long, String> idToCode(long projectId) {
            return codeById;
        }

        @Override
        public List<String> listCaseCodes(long projectId) {
            return List.copyOf(codeById.values());
        }

        @Override
        public com.qa.autotest.domain.common.PageResult<com.qa.autotest.domain.caseasset.TestCase>
                page(long projectId, String keyword, String domain, String priority,
                     String reviewStatus, String capability, String safetyLevel, int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<com.qa.autotest.domain.caseasset.TestCase> find(long projectId, long id) {
            return Optional.empty();
        }

        @Override
        public Optional<com.qa.autotest.domain.caseasset.TestCase> findByCode(long projectId,
                String caseCode) {
            return Optional.empty();
        }

        @Override
        public long insert(com.qa.autotest.domain.caseasset.TestCase testCase, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update(com.qa.autotest.domain.caseasset.TestCase testCase, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(long projectId, long id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.qa.autotest.domain.caseasset.TestCaseStep> steps(long testCaseId) {
            return List.of();
        }

        @Override
        public void replaceSteps(long testCaseId,
                List<com.qa.autotest.domain.caseasset.TestCaseStep> steps, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void writeImportMetadata(long testCaseId, ImportMetadata meta, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void insertImportedSteps(long testCaseId, List<ImportedStep> steps, int sourceRow,
                Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateReview(long projectId, long id, String reviewStatus, String comment,
                Long reviewedBy, java.time.Instant reviewedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updatePriority(long projectId, long id, String priority, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateDomainTag(long projectId, long id, String domainTag, Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void updateSafetyLevel(long projectId, long id, String safetyLevel,
                Long operatorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean codeExists(long projectId, String caseCode, Long excludeId) {
            return false;
        }

        @Override
        public long countByProject(long projectId) {
            return codeById.size();
        }

        @Override
        public List<com.qa.autotest.domain.caseasset.CapabilityStat> capabilities(long projectId) {
            return List.of();
        }
    }
}
