package com.qa.autotest.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** EVID-03：三轮独立证据编排与判定（纯逻辑单测）。 */
class ThreeRoundVerdictTest {

    private static final String SERVICE_SRC =
            "src/main/java/com/qa/autotest/application/execution/RoundVerdictService.java";
    private static final String RESULT_SRC =
            "src/main/java/com/qa/autotest/application/execution/RoundResult.java";
    private static final String MIGRATION =
            "db/migration/V11_13__run_frozen_round.sql";

    private final RoundVerdictService service = new RoundVerdictService();

    private static RoundResult r(String runId, int round, String verdict) {
        return new RoundResult(runId, round, verdict);
    }

    @Test
    void 零轮判定不完整() {
        assertThat(service.evaluate(List.of())).isEqualTo(RoundVerdictService.Verdict.INCOMPLETE);
        assertThat(RoundVerdictService.Verdict.INCOMPLETE.label()).contains("不完整");
    }

    @Test
    void 只跑两轮判定不完整() {
        List<RoundResult> two = List.of(r("RUN-1", 1, "PASS"), r("RUN-2", 2, "PASS"));
        assertThat(service.evaluate(two)).isEqualTo(RoundVerdictService.Verdict.INCOMPLETE);
    }

    @Test
    void 三轮全PASS才PASS() {
        List<RoundResult> three = List.of(
                r("RUN-1", 1, "PASS"), r("RUN-2", 2, "PASS"), r("RUN-3", 3, "PASS"));
        assertThat(service.evaluate(three)).isEqualTo(RoundVerdictService.Verdict.PASS);
    }

    @Test
    void 三轮里有FAIL不判PASS() {
        List<RoundResult> three = List.of(
                r("RUN-1", 1, "PASS"), r("RUN-2", 2, "FAIL"), r("RUN-3", 3, "PASS"));
        assertThat(service.evaluate(three)).isEqualTo(RoundVerdictService.Verdict.FAIL);
        assertThat(service.evaluate(three)).isNotEqualTo(RoundVerdictService.Verdict.PASS);
    }

    @Test
    void 编排产出3个独立RUN_ID与round_no_1_2_3() {
        List<RoundResult> rounds = service.orchestrate(
                List.of("RUN-A", "RUN-B", "RUN-C"), List.of("PASS", "PASS", "PASS"));
        assertThat(rounds).extracting(RoundResult::runId)
                .containsExactly("RUN-A", "RUN-B", "RUN-C");
        assertThat(rounds).extracting(RoundResult::roundNo).containsExactly(1, 2, 3);
        assertThat(rounds.stream().map(RoundResult::runId).distinct().count()).isEqualTo(3);
        assertThat(service.evaluate(rounds)).isEqualTo(RoundVerdictService.Verdict.PASS);
    }

    @Test
    void 重复RUN_ID被拒() {
        assertThrows(IllegalArgumentException.class, () -> service.orchestrate(
                List.of("RUN-1", "RUN-1", "RUN-3"), List.of("PASS", "PASS", "PASS")));
        assertThrows(IllegalArgumentException.class, () -> service.evaluate(List.of(
                r("RUN-1", 1, "PASS"), r("RUN-1", 2, "PASS"), r("RUN-3", 3, "PASS"))));
    }

    @Test
    void 缺轮不得用其它轮补齐_不跨轮拼() {
        // 缺 round 2，却有 3 条（1/3/3）：想用第三条凑数，仍判不完整，不把 1/3 拼成 PASS
        List<RoundResult> missingRound2 = List.of(
                r("RUN-1", 1, "PASS"), r("RUN-3", 3, "PASS"), r("RUN-4", 3, "PASS"));
        assertThat(service.evaluate(missingRound2))
                .isEqualTo(RoundVerdictService.Verdict.INCOMPLETE);
        // 多出 round 4 也不能顶替缺失的 round 3
        List<RoundResult> extraRound = List.of(
                r("RUN-1", 1, "PASS"), r("RUN-2", 2, "PASS"), r("RUN-4", 4, "PASS"));
        assertThat(service.evaluate(extraRound))
                .isEqualTo(RoundVerdictService.Verdict.INCOMPLETE);
    }

    @Test
    void 历史round_no为NULL不回填_迁移与源码静态断言() throws Exception {
        String migration = source(MIGRATION);
        assertThat(migration).contains("round_no", "历史 run 为 NULL", "不回填");
        String service = source(SERVICE_SRC);
        assertThat(service.toLowerCase(Locale.ROOT))
                .doesNotContain("update run", "backfill", "update round_no", "set round_no");
        String result = source(RESULT_SRC);
        assertThat(result).contains("record RoundResult");
    }

    private static Path repoFile(String relative) {
        Path fromBackend = Path.of("..", relative);
        return Files.isRegularFile(fromBackend) ? fromBackend : Path.of(relative);
    }

    private static String source(String relative) throws Exception {
        return Files.readString(repoFile(relative));
    }
}
