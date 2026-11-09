package com.qa.autotest.application.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EW32-10 日志吞吐探针（TC-3.2-17「运行链日志 ≥5000 行/s 且不丢行」）。
 *
 * <p>两条链路分别测：
 * <ol>
 *   <li>实时频道 {@link RunLogService#publish}：写环形缓冲 + 待发队列，供前端订阅；</li>
 *   <li>全量落盘：与 {@code PabotOrchestrator.append} 相同的逐行
 *       {@code Files.writeString(CREATE, APPEND)}，保证 run.log 一行不落。</li>
 * </ol>
 *
 * <p>数字只打印不断言绝对耗时（避免 CI 机器抖动误报），阈值取预算下限 5000 行/s，
 * 余量在一个数量级以上；断言同时校验行数完整，防止「写得快但丢行」。
 */
class LogThroughputProbeTest {

    private static final int ROWS = 50_000;
    private static final int MIN_ROWS_PER_SECOND = 5_000;

    @Test
    void 实时频道发布吞吐不低于五千行每秒() {
        RunLogService service = new RunLogService(Clock.systemUTC());
        String runId = "probe-log-throughput";
        long started = System.nanoTime();
        for (int i = 0; i < ROWS; i++) {
            service.publish(runId, "pabot", "[pabot] line " + i);
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        double rowsPerSecond = ROWS / seconds;
        System.out.printf("LOG_PUBLISH_ROWS_PER_SECOND=%.0f (rows=%d seconds=%.3f)%n",
                rowsPerSecond, ROWS, seconds);
        assertTrue(rowsPerSecond >= MIN_ROWS_PER_SECOND,
                "实时频道发布吞吐 " + (long) rowsPerSecond + " 行/s < " + MIN_ROWS_PER_SECOND);
    }

    @Test
    void 全量落盘逐行追加不丢行且吞吐达标(@TempDir Path tempDir) throws IOException {
        Path runLog = tempDir.resolve("run.log");
        long started = System.nanoTime();
        for (int i = 0; i < ROWS; i++) {
            append(runLog, "[pabot] line " + i);
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        double rowsPerSecond = ROWS / seconds;
        System.out.printf("LOG_DISK_ROWS_PER_SECOND=%.0f (rows=%d seconds=%.3f)%n",
                rowsPerSecond, ROWS, seconds);
        assertTrue(rowsPerSecond >= MIN_ROWS_PER_SECOND,
                "全量落盘吞吐 " + (long) rowsPerSecond + " 行/s < " + MIN_ROWS_PER_SECOND);

        List<String> lines = Files.readAllLines(runLog, StandardCharsets.UTF_8);
        assertEquals(ROWS, lines.size(), "run.log 行数不完整（丢行）");
        assertEquals("[pabot] line 0", lines.get(0));
        assertEquals("[pabot] line " + (ROWS - 1), lines.get(lines.size() - 1));
    }

    /** 与 {@code PabotOrchestrator#append} 完全一致的落盘方式（逐行打开追加）。 */
    private static void append(Path path, String line) throws IOException {
        Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
