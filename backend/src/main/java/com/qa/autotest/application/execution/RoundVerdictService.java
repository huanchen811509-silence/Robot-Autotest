package com.qa.autotest.application.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * EVID-03：三轮独立证据编排与判定。
 *
 * <p>三轮用 3 个互不复用的独立 RUN_ID，round_no 分别 1/2/3；历史 run 的 round_no 为 NULL，
 * 只用于新轮次，绝不回填历史数据。判定逐轮独立进行：<b>不跨轮拼</b>——缺任一轮不得
 * 用其它轮的结果补齐，只跑 0/2 轮一律「不完整」；只有 3 轮全 PASS 才 PASS，有 FAIL 即 FAIL。
 */
public class RoundVerdictService {

    /** 结论枚举；INCOMPLETE 中文标签「不完整」。 */
    public enum Verdict {
        PASS("PASS"),
        FAIL("FAIL"),
        INCOMPLETE("不完整");

        private final String label;

        Verdict(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 三轮编排：校验 3 个 RUN_ID 互不复用，产出 round_no 1/2/3 的独立轮次。 */
    public List<RoundResult> orchestrate(List<String> runIds, List<String> verdicts) {
        if (runIds == null || runIds.size() != 3) {
            throw new IllegalArgumentException("三轮编排必须给 3 个独立 RUN_ID");
        }
        if (runIds.stream().distinct().count() != 3) {
            throw new IllegalArgumentException("3 个 RUN_ID 必须互不复用");
        }
        List<RoundResult> rounds = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String verdict = verdicts == null ? "PASS" : verdicts.get(i);
            rounds.add(new RoundResult(runIds.get(i), i + 1, verdict));
        }
        return rounds;
    }

    /**
     * 判定：重复 RUN_ID 被拒；必须恰好覆盖 round 1/2/3 且只有 3 条，否则 INCOMPLETE；
     * 3 轮全 PASS 才 PASS，任一 FAIL 即 FAIL。绝不把不同轮结果合并成一条结论。
     */
    public Verdict evaluate(List<RoundResult> results) {
        List<RoundResult> safe = results == null ? List.of() : results;
        long distinctRunIds = safe.stream().map(RoundResult::runId).distinct().count();
        if (distinctRunIds != safe.size()) {
            throw new IllegalArgumentException("重复 RUN_ID：三轮必须是 3 个独立 RUN_ID，互不复用");
        }
        boolean roundsComplete = safe.size() == 3
                && safe.stream().anyMatch(r -> r.roundNo() == 1)
                && safe.stream().anyMatch(r -> r.roundNo() == 2)
                && safe.stream().anyMatch(r -> r.roundNo() == 3)
                && safe.stream().map(RoundResult::roundNo).distinct().count() == 3;
        if (!roundsComplete) {
            return Verdict.INCOMPLETE;
        }
        boolean allPass = safe.stream().allMatch(r -> "PASS".equals(r.verdict()));
        return allPass ? Verdict.PASS : Verdict.FAIL;
    }
}
