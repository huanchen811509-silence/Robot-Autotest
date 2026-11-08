package com.qa.autotest.application.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** EVID-02：证据 LOG 五要素 / 真实退出码 / 缺要素 missing / 凭据脱敏（纯逻辑单测）。 */
class EvidenceLogFormatTest {

    private static final String EVID02_SRC =
            "src/main/java/com/qa/autotest/application/execution/EvidenceLogWriter.java";

    private final EvidenceLogWriter writer = new EvidenceLogWriter();

    @Test
    void 一轮证据五要素齐全() throws Exception {
        List<String> cmd = List.of("sh", "-c",
                "printf 'hello'; printf 'warn' >&2; exit 3");
        EvidenceLogWriter.Captured captured = EvidenceLogWriter.capture(cmd);
        String log = writer.write("node-a", 2, "采集吞吐基线", cmd, captured);

        assertThat(log).contains("[node-a]");                       // 1. [hostname]
        assertThat(log).contains("#步骤2说明 采集吞吐基线");          // 2. #步骤N说明
        assertThat(log).contains("$ sh -c");                         // 3. 命令原文
        assertThat(log).contains("--- stdout ---", "hello");         // 4a. stdout
        assertThat(log).contains("--- stderr ---", "warn");          // 4b. stderr
        assertThat(log).contains("exit_code=3");                     // 5. 真实退出码
    }

    @Test
    void 真实退出码来自ProcessBuilder_随命令变化而非写死常量() throws Exception {
        int zero = EvidenceLogWriter.capture(List.of("sh", "-c", "exit 0")).exitCode();
        int seven = EvidenceLogWriter.capture(List.of("sh", "-c", "exit 7")).exitCode();
        assertThat(zero).isZero();
        assertThat(seven).isEqualTo(7);
        assertThat(zero).isNotEqualTo(seven);                  // 证明不是写死常量

        List<String> cmd = List.of("sh", "-c", "exit 7");
        String log = writer.write("node-a", 1, "取真实退出码",
                cmd, EvidenceLogWriter.capture(cmd));
        assertThat(log).contains("exit_code=7");
    }

    @Test
    void 缺任一要素时输出显式标missing_不静默补齐() {
        String log = writer.write(null, 1, null, List.of(), null);
        assertThat(log).contains("[missing]");
        assertThat(log).contains("#步骤1说明 missing");
        assertThat(log).contains("$ missing");
        assertThat(log).contains("--- stdout ---\nmissing");
        assertThat(log).contains("--- stderr ---\nmissing");
        assertThat(log).contains("exit_code=missing");
        assertThat(log).doesNotContain("exit_code=0");         // 没拿常量 0 补齐
    }

    @Test
    void LOG不写入凭据_脱敏生效() {
        EvidenceLogWriter.Captured captured =
                new EvidenceLogWriter.Captured(0, "token=abc123\n", "");
        String log = writer.write("node-a", 1, "带凭据命令",
                List.of("tool", "--password=hunter2"), captured);
        assertThat(log).doesNotContain("hunter2", "abc123");
        assertThat(log).contains("password=<redacted>", "token=<redacted>");
    }

    @Test
    void 生产源码用ProcessBuilder且取exitValue_不写死退出码() throws Exception {
        String src = source(EVID02_SRC);
        assertThat(src).contains("new ProcessBuilder", "exitValue()", "waitFor()");
        assertThat(src).contains("MISSING", "missing", "scrub");
    }

    private static Path repoFile(String relative) {
        Path fromBackend = Path.of("..", relative);
        return Files.isRegularFile(fromBackend) ? fromBackend : Path.of(relative);
    }

    private static String source(String relative) throws Exception {
        return Files.readString(repoFile(relative));
    }
}
