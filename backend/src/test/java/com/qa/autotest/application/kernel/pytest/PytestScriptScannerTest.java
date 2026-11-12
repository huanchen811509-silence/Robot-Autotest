package com.qa.autotest.application.kernel.pytest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 脚本扫描（W51-08）。
 *
 * <p>测的是三条实测事实：① 编号从 docstring 读，不是文件名；
 * ② 无编号声明的文件（框架自测）不算用例；③ 双端用例按 TRAFFIC_DIRECTION 识别。
 */
class PytestScriptScannerTest {

    private static final String CASE_FILE = """
            \"\"\"
            用例编号: IM-NET-131
            用例名称    : 实际RX Checksum卸载生效
            对端对象    : TX NIC
            \"\"\"
            import pytest

            TRAFFIC_DIRECTION = "reverse"


            @pytest.mark.nic
            def test_im_net_131(nic_pair):
                assert nic_pair
            """;

    private static final String SELF_TEST_FILE = """
            \"\"\"框架自测，没有编号声明。\"\"\"
            def test_a():
                pass

            def test_b():
                pass
            """;

    @Test
    void 编号从docstring读而不是文件名(@TempDir Path repo) throws IOException {
        write(repo, "tests/nic/ethernet/test_wrong_name.py", CASE_FILE);

        List<PytestScriptScanner.ScannedScript> scanned = PytestScriptScanner.scan(repo);

        assertThat(scanned).hasSize(1);
        assertThat(scanned.get(0).caseCode()).isEqualTo("IM-NET-131");
        assertThat(scanned.get(0).resolveSource()).isEqualTo("DOCSTRING");
        assertThat(scanned.get(0).relativePath()).isEqualTo("tests/nic/ethernet/test_wrong_name.py");
        assertThat(scanned.get(0).nodeId())
                .isEqualTo("tests/nic/ethernet/test_wrong_name.py::test_im_net_131");
    }

    @Test
    void 无编号声明按文件名降级并标记来源(@TempDir Path repo) throws IOException {
        write(repo, "tests/nic/base/test_im_fun_020.py", """
                \"\"\"没有编号声明。\"\"\"
                def test_im_fun_020():
                    pass
                """);

        List<PytestScriptScanner.ScannedScript> scanned = PytestScriptScanner.scan(repo);

        assertThat(scanned).hasSize(1);
        assertThat(scanned.get(0).caseCode()).isEqualTo("IM-FUN-020");
        assertThat(scanned.get(0).resolveSource()).isEqualTo("FILENAME");
    }

    @Test
    void 无编号且文件名不成形时不算用例(@TempDir Path repo) throws IOException {
        write(repo, "tests/test_config_and_plan.py", SELF_TEST_FILE);

        assertThat(PytestScriptScanner.scan(repo)).isEmpty();
    }

    @Test
    void 双端用例按TRAFFIC_DIRECTION识别(@TempDir Path repo) throws IOException {
        write(repo, "tests/nic/ethernet/test_im_net_131.py", CASE_FILE);
        write(repo, "tests/nic/base/test_im_fun_020.py", """
                \"\"\"用例编号: IM-FUN-020\"\"\"
                TARGET_SLOT = "first"

                def test_im_fun_020():
                    pass
                """);

        List<PytestScriptScanner.ScannedScript> scanned = PytestScriptScanner.scan(repo);

        assertThat(scanned).hasSize(2);
        PytestScriptScanner.ScannedScript net = scanned.stream()
                .filter(s -> s.caseCode().equals("IM-NET-131")).findFirst().orElseThrow();
        PytestScriptScanner.ScannedScript fun = scanned.stream()
                .filter(s -> s.caseCode().equals("IM-FUN-020")).findFirst().orElseThrow();
        assertThat(net.requiresPeer()).isTrue();
        assertThat(fun.requiresPeer()).isFalse();
    }

    @Test
    void 跳过产物与虚拟环境目录(@TempDir Path repo) throws IOException {
        write(repo, "tests/nic/test_im_net_131.py", CASE_FILE);
        write(repo, "artifacts/20260101/test_old.py", CASE_FILE);
        write(repo, ".venv/lib/test_dep.py", CASE_FILE);
        write(repo, "tests/__pycache__/test_cache.py", CASE_FILE);

        List<PytestScriptScanner.ScannedScript> scanned = PytestScriptScanner.scan(repo);

        assertThat(scanned).hasSize(1);
        assertThat(scanned.get(0).relativePath()).isEqualTo("tests/nic/test_im_net_131.py");
    }

    @Test
    void 仓库不存在时抛异常而不是返回空(@TempDir Path repo) {
        assertThatThrownBy(() -> PytestScriptScanner.scan(repo.resolve("nope")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("仓库根不存在");
    }

    private static void write(Path repo, String relative, String content) throws IOException {
        Path file = repo.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
