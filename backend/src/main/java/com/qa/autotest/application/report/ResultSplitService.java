package com.qa.autotest.application.report;

import com.qa.autotest.application.repository.ExecModeStatsRepository;
import com.qa.autotest.application.repository.ExecModeStatsRepository.ModeCount;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * 自动 / 手工结果分离统计（FR-v5.4-05 / D12–D13 / W54-05）。
 *
 * <p><b>为什么必须分列</b>：手工结果与自动结果的证据强度完全不同——自动结果背后是
 * 一条可复现的命令与产物，手工结果背后是一个人的确认。合并成一个通过率之后，
 * 「这个项目 92% 通过」这句话既可能指「自动化很稳」，也可能指「大部分是人工点的」，
 * 而这两种情况对能不能发版的含义完全相反。
 *
 * <p><b>三条硬规则</b>：
 * <ol>
 *   <li>合计必须等于自动 + 手工（{@link Snapshot#consistent()}），不是「各自算完再挑一个展示」；</li>
 *   <li>合计必须标注「含手工 N 条」——不标的话，读者会把合计当成自动结果；</li>
 *   <li>对外口径默认只报自动（{@link Snapshot#external()}），手工要显式勾选。</li>
 * </ol>
 * 第三条最容易漏：对外报告一旦把手工结果算进去，收报告的人无法区分
 * 「你们自动化做得好」和「你们人工测得挺勤」。
 */
@Service
public class ResultSplitService {

    private final ExecModeStatsRepository repository;
    private final Clock clock;

    public ResultSplitService(ExecModeStatsRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** 一种执行方式的统计（含通过率）。 */
    public record ModeStat(String execMode, int total, int passed, int failed,
                           double passRatePercent) {
    }

    /**
     * 分离统计结果。
     *
     * @param total          合计条数 = auto.total + manual.total
     * @param passed         合计通过 = auto.passed + manual.passed
     * @param passRatePercent 合计通过率
     * @param manualNote     合计的标注文案（含手工条数），前端直接展示
     * @param consistent     合计是否等于两项之和（false 说明取数有漏，页面应显示告警而不是数字）
     * @param unknownModes   既不是 auto 也不是 manual 的执行方式（如实列出，不静默并入 auto）
     */
    public record Snapshot(ModeStat auto, ModeStat manual, ModeStat total, String manualNote,
                           boolean consistent, List<String> unknownModes) {

        /** 对外口径：只有自动结果。 */
        public ModeStat external() {
            return auto;
        }

        /** 是否含手工结果（决定页面要不要显示分离列）。 */
        public boolean hasManual() {
            return manual.total() > 0;
        }
    }

    /** 近 {@code days} 天的分离统计；{@code runId} 非空时只统计该 run。 */
    public Snapshot stats(long projectId, int days, String runId) {
        Instant since = clock.instant().minus(Duration.ofDays(Math.max(1, days)));
        return summarize(repository.byExecMode(projectId, since, runId));
    }

    /** 纯函数聚合（可单测）。 */
    Snapshot summarize(List<ModeCount> counts) {
        List<ModeCount> rows = counts == null ? List.of() : counts;
        int autoTotal = 0;
        int autoPassed = 0;
        int manualTotal = 0;
        int manualPassed = 0;
        List<String> unknown = new java.util.ArrayList<>();

        for (ModeCount row : rows) {
            String mode = row.execMode() == null ? "auto"
                    : row.execMode().trim().toLowerCase(Locale.ROOT);
            switch (mode) {
                case "auto" -> {
                    autoTotal += row.total();
                    autoPassed += row.passed();
                }
                case "manual" -> {
                    manualTotal += row.total();
                    manualPassed += row.passed();
                }
                default -> {
                    // 未知执行方式不并入 auto：那会让「自动通过率」悄悄包含来路不明的数据
                    unknown.add(row.execMode() + ":" + row.total());
                }
            }
        }

        ModeStat auto = stat("auto", autoTotal, autoPassed);
        ModeStat manual = stat("manual", manualTotal, manualPassed);
        ModeStat total = stat("total", autoTotal + manualTotal, autoPassed + manualPassed);

        boolean consistent = total.total() == autoTotal + manualTotal
                && total.passed() == autoPassed + manualPassed;
        String note = manualTotal > 0
                ? "合计含手工 " + manualTotal + " 条（自动 " + autoTotal + " 条）"
                : "合计全部为自动结果";
        return new Snapshot(auto, manual, total, note, consistent, List.copyOf(unknown));
    }

    private static ModeStat stat(String mode, int total, int passed) {
        int failed = Math.max(0, total - passed);
        return new ModeStat(mode, total, passed, failed, percent(passed, total));
    }

    private static double percent(int part, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(part * 1000.0 / total) / 10.0;
    }
}
