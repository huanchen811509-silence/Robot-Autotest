package com.qa.autotest.application.execution;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 产物校验：退出码不可信，必须满足「存在 + size>1024 + 可解析 + totalTests>0」四条件。
 *
 * <p>2026-11-04 补两条漏洞（实测发现，见 docs/evidence/v0.4/exe03-fault-injection.md）：
 *
 * <ol>
 *   <li><b>期望条数</b>：原来只判 {@code totalTests > 0}。pabot 4 分片里杀掉 1 个，
 *       {@code output.xml} 只剩 6 条（应 8 条），{@code 6 > 0} 照样返回 ok ——
 *       用例凭空消失会被当成全绿。调用方知道应跑多少条时，用
 *       {@link #verify(Path, int)} 传进来。</li>
 *   <li><b>分片完整性</b>：被杀分片的 {@code pabot_results/N/output.xml} 是 <b>0 字节</b>
 *       （正常分片约 2.7 KB）。这个信号不需要任何元数据就能判定，故单独成
 *       {@link #checkShards(Path)}。</li>
 * </ol>
 */
public final class ArtifactVerifier {

    /** 小于此值视为「没写出东西」——pabot 中断时常见 0 字节 output.xml。 */
    private static final long MIN_BYTES = 1024;

    private final OutputXmlProbe probe;

    public ArtifactVerifier(OutputXmlProbe probe) {
        this.probe = probe;
    }

    /** 不校验条数（调用方不知道应跑多少条）。行为与 2026-11-04 之前一致。 */
    public VerifyResult verify(Path outputXml) {
        return verify(outputXml, 0);
    }

    /**
     * @param expectedCaseCount 本次应执行的用例数；{@code <=0} 表示未知，跳过条数校验
     */
    public VerifyResult verify(Path outputXml, int expectedCaseCount) {
        if (outputXml == null || !Files.exists(outputXml)) {
            return new VerifyResult(false, "missing", 0, 0, expectedCaseCount);
        }
        long size;
        try {
            size = Files.size(outputXml);
        } catch (Exception ex) {
            return new VerifyResult(false, "missing", 0, 0, expectedCaseCount);
        }
        if (size <= MIN_BYTES) {
            return new VerifyResult(false, "too_small", 0, size, expectedCaseCount);
        }
        OutputXmlProbe.Probe probeResult = probe.probe(outputXml);
        if (!probeResult.parsed()) {
            return new VerifyResult(false, "parse_failed", 0, size, expectedCaseCount);
        }
        int total = probeResult.totalTests();
        // v5.24.0：三态统计与「产物是否有效」是两个正交结论。产物哪怕只跑了一半，
        // 已落定的用例结论仍然是真的——诊断「到底几条过、几条挂」必须靠它。
        OutputXmlProbe.Stats stats = probe.stats(outputXml);
        if (total <= 0) {
            return new VerifyResult(false, "no_tests_executed", 0, size, expectedCaseCount, stats);
        }
        if (expectedCaseCount > 0 && total < expectedCaseCount) {
            // 少 = 有用例没跑到，必须判失败。多 = 重复执行或收集重复，同样不可信。
            return new VerifyResult(false, "partial_results", total, size, expectedCaseCount, stats);
        }
        if (expectedCaseCount > 0 && total > expectedCaseCount) {
            return new VerifyResult(false, "unexpected_extra", total, size, expectedCaseCount, stats);
        }
        return new VerifyResult(true, "ok", total, size, expectedCaseCount, stats);
    }

    /**
     * 产物里是否存在「单用例超时」失败（FR-v0.4-24）。
     *
     * <p>与 run 总超时区分：这是 RF 套件级 {@code Test Timeout} 到点把单条用例判失败，
     * 其余用例照常跑完，run 的原因记为 {@code test_timeout}。
     */
    public boolean testTimedOut(Path outputXml) {
        return probe.timedOutTests(outputXml) > 0;
    }

    /**
     * 分片完整性：{@code pabot_results/} 下每个分片目录的 {@code output.xml} 都必须非空。
     *
     * <p>只看「已存在的分片」，不要求分片数等于进程数 —— pabot 在用例少于进程数时
     * 会少建分片，那是正常行为。这里要抓的是「分片建了、跑了一半被打断」。
     *
     * <p><b>没有 {@code pabot_results} 目录时不判失败</b>：单分片执行、测试替身、
     * 或换用别的执行器时都可能没有这个目录，判失败会误伤。本方法只回答
     * 「有分片的话，它们是否都完整」。
     *
     * @param outputDir pabot 的 {@code --outputdir}
     */
    public ShardIntegrity checkShards(Path outputDir) {
        if (outputDir == null || !Files.isDirectory(outputDir)) {
            return new ShardIntegrity(true, "no_shard_dir", List.of());
        }
        Path base = outputDir.resolve("pabot_results");
        if (!Files.isDirectory(base)) {
            return new ShardIntegrity(true, "no_shard_dir", List.of());
        }
        List<Integer> empty = new ArrayList<>();
        int shards = 0;
        try (Stream<Path> children = Files.list(base)) {
            for (Path dir : children.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                if (!name.chars().allMatch(Character::isDigit)) {
                    continue; // 非分片目录跳过
                }
                shards++;
                Path xml = dir.resolve("output.xml");
                try {
                    if (!Files.exists(xml) || Files.size(xml) < MIN_BYTES) {
                        empty.add(Integer.valueOf(name));
                    }
                } catch (Exception ex) {
                    empty.add(Integer.valueOf(name));
                }
            }
        } catch (Exception ex) {
            return new ShardIntegrity(true, "no_shard_dir", List.of());
        }
        if (shards == 0) {
            return new ShardIntegrity(true, "no_shard_dir", List.of());
        }
        if (!empty.isEmpty()) {
            return new ShardIntegrity(false, "shard_incomplete", List.copyOf(empty));
        }
        return new ShardIntegrity(true, "ok", List.of());
    }

    /**
     * @param expectedTests 调用方期望的用例数；0 表示未知
     * @param stats         逐用例三态统计；产物不可读时 {@link OutputXmlProbe.Stats#unknown(int)}
     */
    public record VerifyResult(boolean success, String reason, int totalTests, long sizeBytes,
                               int expectedTests, OutputXmlProbe.Stats stats) {

        public VerifyResult {
            stats = stats == null ? OutputXmlProbe.Stats.unknown(totalTests) : stats;
        }

        /** 兼容旧调用：不涉及期望条数时用这个。 */
        public VerifyResult(boolean success, String reason, int totalTests, long sizeBytes) {
            this(success, reason, totalTests, sizeBytes, 0);
        }

        public VerifyResult(boolean success, String reason, int totalTests, long sizeBytes,
                            int expectedTests) {
            this(success, reason, totalTests, sizeBytes, expectedTests,
                    OutputXmlProbe.Stats.unknown(totalTests));
        }
    }

    /**
     * @param emptyShards 产物为空的（或缺失的）分片编号，便于日志直接指出是哪个分片
     */
    public record ShardIntegrity(boolean ok, String reason, List<Integer> emptyShards) {
    }
}
