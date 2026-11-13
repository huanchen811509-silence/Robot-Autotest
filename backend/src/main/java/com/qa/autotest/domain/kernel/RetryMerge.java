package com.qa.autotest.domain.kernel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多轮结果合并（v5.6.0 / FR-v5.6-24）：首轮 + N 次复测 → 一条最终结论。
 *
 * <p><b>为什么合并规则必须只有一处</b>：执行侧归一、报告入库重解析、导出的明细，
 * 三条链路都会拿到「同一批用例的多轮结果」。如果每条链路各写一遍「末轮 PASS 就算过」，
 * 那么只要有一处把 ERROR 当成可复测、另一处把 XPASS 当成不可复测，同一批产物就会
 * 得出两个失败数——而这两个数一个进报告、一个进看板，谁也说不清哪个是真的。
 *
 * <p><b>判定口径</b>（三条，都可判定）：
 * <ol>
 *   <li>某用例只在后轮出现：按后轮结论记，{@code retryCount} = 该轮序号。
 *       真实场景是「首轮没收集到、复测才收集到」，不能因为首轮没它就丢结果。</li>
 *   <li>首轮失败（{@link CaseStatus#isFailure()}）、任一轮通过：最终记末轮通过，
 *       {@code flaky = true}，{@code retryCount} = 通过那一轮的序号（0 基）。</li>
 *   <li>各轮都失败：最终记**末轮**结论与消息（最后一次失败才是当前状态），
 *       {@code flaky = false}——稳定失败不是抖动，把它算成抖动等于掩盖缺陷。</li>
 * </ol>
 *
 * <p>顺序保持「首轮出现顺序」，后轮新增的用例追加在末尾：报告里的行序不该因为
 * 复测而整体重排，否则人眼对不上上一次看的报告。
 */
public final class RetryMerge {

    private RetryMerge() {
    }

    /**
     * 合并多轮运行。
     *
     * @param rounds 按轮次升序；{@code rounds.get(0)} 是首轮。空列表返回空运行，不抛异常
     *               （调用方在「没有产物」时已经各自判过，合并层不必重复报错）
     */
    public static NormalizedRun merge(List<NormalizedRun> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            return new NormalizedRun("pytest", "", List.of(), Map.of());
        }
        NormalizedRun first = rounds.get(0);
        List<List<NormalizedCaseResult>> grouped = new ArrayList<>();
        for (NormalizedRun round : rounds) {
            grouped.add(round == null ? List.of() : round.cases());
        }
        return new NormalizedRun(first.kernel(), first.runId(), mergeCases(grouped), first.metadata());
    }

    /**
     * 合并多轮用例结论。{@code rounds} 每个元素是一轮的全部用例。
     *
     * <p>单独暴露这一层是因为归一器按「文件 → 用例」收集，手上没有 {@link NormalizedRun}；
     * 让它为了合并去构造一个假运行，等于把 metadata 的事也搅进来。
     */
    public static List<NormalizedCaseResult> mergeCases(
            List<List<NormalizedCaseResult>> rounds) {
        if (rounds == null || rounds.isEmpty()) {
            return List.of();
        }
        if (rounds.size() == 1) {
            return List.copyOf(rounds.get(0) == null ? List.of() : rounds.get(0));
        }
        Map<String, NormalizedCaseResult> merged = new LinkedHashMap<>();
        // 首轮失败过没有：抖动判定只看「首轮」与「末轮」，中间轮次不参与定性。
        Map<String, Boolean> failedFirstRound = new LinkedHashMap<>();
        for (int round = 0; round < rounds.size(); round++) {
            List<NormalizedCaseResult> cases = rounds.get(round);
            if (cases == null) {
                continue;
            }
            for (NormalizedCaseResult current : cases) {
                if (current == null) {
                    continue;
                }
                String key = key(current);
                NormalizedCaseResult previous = merged.get(key);
                if (previous == null) {
                    failedFirstRound.put(key, round == 0 && current.status().isFailure());
                    // 后轮才出现的用例：轮次必须记，否则「首轮没收集到、复测才收到」
                    // 会被当成「首轮就跑到了」，抖动统计与耗时口径都会错。
                    merged.put(key, round == 0 ? current : new NormalizedCaseResult(
                            current.nodeId(), current.name(), current.status(),
                            current.durationSeconds(), current.message(), current.commandText(),
                            current.commandSource(), current.commandExitCode(), round, false));
                    continue;
                }
                merged.put(key, pick(previous, current, round,
                        Boolean.TRUE.equals(failedFirstRound.get(key))));
            }
        }
        return List.copyOf(merged.values());
    }

    /** 合并同一条用例的两轮结论。{@code round} 是当前轮序号（0 基）。 */
    private static NormalizedCaseResult pick(NormalizedCaseResult previous,
                                             NormalizedCaseResult current, int round,
                                             boolean failedFirstRound) {
        boolean previousFailure = previous.status().isFailure();
        boolean currentFailure = current.status().isFailure();
        if (!previousFailure) {
            // 已经是通过/跳过：复测不该把通过改回失败。真实路径也不会重跑它，
            // 万一出现（产物目录混了别的 run），以先到的通过为准更接近事实。
            return previous;
        }
        if (!currentFailure && current.status() != CaseStatus.SKIP
                && current.status() != CaseStatus.XFAIL) {
            // 首轮失败 → 本轮通过：抖动。retryCount 记「又跑了几轮」＝本轮序号。
            return new NormalizedCaseResult(current.nodeId(), current.name(), current.status(),
                    current.durationSeconds(), current.message(), current.commandText(),
                    current.commandSource(), current.commandExitCode(), round,
                    failedFirstRound);
        }
        if (!currentFailure) {
            // 复测被跳过（SKIP / XFAIL）：失败事实不能被「跳过」掩盖，否则一条
            // 环境没起来导致跳过的用例会从失败数里消失。保留失败结论，轮次照记。
            return new NormalizedCaseResult(previous.nodeId(), previous.name(), previous.status(),
                    previous.durationSeconds(), previous.message(), previous.commandText(),
                    previous.commandSource(), previous.commandExitCode(), round, false);
        }
        // 本轮仍失败：保留本轮结论与消息，轮次照记，但不标抖动。
        return new NormalizedCaseResult(current.nodeId(), current.name(), current.status(),
                current.durationSeconds(), current.message(), current.commandText(),
                current.commandSource(), current.commandExitCode(), round, false);
    }

    /** 同一性键：优先 nodeId；nodeId 为空时退回 name（不同内核的 nodeId 语义不同，不能只用 name）。 */
    private static String key(NormalizedCaseResult item) {
        String nodeId = item.nodeId();
        return nodeId == null || nodeId.isBlank() ? "name:" + item.name() : nodeId;
    }
}
