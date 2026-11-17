package com.qa.autotest.application.repository;

import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.report.GateResult;
import com.qa.autotest.domain.report.ParsedReport;
import com.qa.autotest.domain.report.ReportCaseRow;
import com.qa.autotest.domain.report.ReportRecord;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 报告入库与查询仓储（RPT-01）。
 */
public interface ReportRepository {

    /**
     * 用一次解析结果覆盖该运行的报告数据（幂等：先删旧的 test_result / keyword_result 再插入）。
     *
     * @param projectId  项目（用于 run 表租户校验）
     * @param runDbId    run.id（test_result / keyword_result 的 run_id 外键指向它）
     * @param parsed     解析结果
     * @param gate       metadata 门禁结果
     * @param startedAt  metadata.STARTED_AT，为 null 时保留 run 原值
     */
    void replace(long projectId, long runDbId, ParsedReport parsed, GateResult gate, Instant startedAt);

    /** 按 RUN_ID 查报告视图。 */
    Optional<ReportRecord> find(long projectId, String runId);

    /** 分页查询项目下的报告视图。 */
    PageResult<ReportRecord> page(long projectId, int page, int size);

    /**
     * 逐条用例明细（v5.22.0 / FR-v5.22-02）：冻结时写进快照，导出时不再回查。
     *
     * <p>口径：按 {@code test_result.id} 升序（= 执行顺序）；{@code case_code} 优先取台账
     * {@code test_case.case_code}，没有台账行（如纯 pytest 场景）回退 {@code case_key}，
     * 保证任何 run 都能给出可追溯的编号而不是空列。
     */
    List<ReportCaseRow> cases(long projectId, String runId);

    /**
     * 回写门禁结论（HRD-02 补录后重算）：只改 {@code metadata_missing} / {@code exportable}，
     * 不动解析出的 {@code metadata_json} —— 补录值在读取时叠加（{@code MetadataOverlay}）。
     */
    void updateGate(long projectId, String runId, GateResult gate);
}
