package com.qa.autotest.application.repository;

import java.util.List;

/**
 * run 级失败归因取数（W52-10）。
 *
 * <p>两次查询分开给：一次要「逐行样本」（带失败原文，用于归因与 TopN），
 * 一次只要「计数」（用于分母）。合成一条 SQL 就得把逐行结果在应用层再数一遍，
 * 而这两个数必须来自同一快照——分开查、同一事务内取，比一条 SQL 里既 SELECT 明细
 * 又 SUM 汇总更不容易写错。
 */
public interface RunFailureBreakdownRepository {

    /**
     * 一行非 PASS 结果。
     *
     * @param caseCode   用例编号（{@code test_case.code}，退化为 {@code case_key}）
     * @param kernel     robot / pytest
     * @param verdict    原始结论（FAIL/ERROR/SKIP/DISPATCHED/…）
     * @param failureMsg 失败原文（可空；空文本会被归到「未归类」）
     */
    record Row(String caseCode, String kernel, String verdict, String failureMsg) {
    }

    /**
     * 计数。
     *
     * @param total   该 run 的结果行总数
     * @param passed  PASS 数
     * @param planned 计划未执行数（PLANNED / DISPATCHED / PENDING —— 已派发未回也算未执行）
     */
    record Counts(int total, int passed, int planned) {
    }

    /** 非 PASS 的结果行（按 id 升序，保证同一 run 两次取数顺序一致）。 */
    List<Row> nonPassedRows(long projectId, String runId);

    /** 计数（与 {@link #nonPassedRows} 同一 run 口径）。 */
    Counts counts(long projectId, String runId);
}
