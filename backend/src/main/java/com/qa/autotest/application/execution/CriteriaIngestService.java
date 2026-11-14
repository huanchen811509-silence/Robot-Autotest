package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 判据查询门面（v5.7.0 FR-v5.7-12 / 13，复核 W57-50 后重构）。
 *
 * <p><b>写入在哪</b>：{@code test_result.criteria_*} 三列由
 * {@code infrastructure.ReportRepositoryJdbc} 在入库 {@code test_result} 的同一语句里写，
 * 评估逻辑是纯函数 {@link CriteriaEvaluation}。放在入库处的原因是「判据事实只产生一次」——
 * 单独开一条采集通道，就会出现「报告已入库但判据还没到」的窗口期，报告结论随之抖动。
 *
 * <p><b>本类为什么只剩读</b>：v5.7.0 首版在这里实现了 {@code ingest / ingestInTenant}，
 * 但**全仓零调用点**，导致 {@code criteria_*} 永不产生、报告结论退回 {@code failCount} 口径
 * （审计 P0-4）。按「要么接上、要么删掉」收口为只读门面，写入统一走上面那条路径，
 * 不允许再出现第二套写入实现。
 */
@Service
public class CriteriaIngestService {

    /** 持久化抽象：SQL 全部参数绑定且带 project_id。 */
    public interface ResultStore {
        List<CriteriaRow> listCriteria(long projectId, String runId);
    }

    /**
     * 判据明细行：{@code verdict} 是用例级 PASS/FAIL/SKIP（展示用），
     * {@code processStatus} 是由 run 状态归一化的 completed/failed（结论矩阵用）。
     */
    public record CriteriaRow(long caseId,
                              String caseCode,
                              String verdict,
                              String processStatus,
                              String criteriaJson,
                              String criteriaVerdict,
                              String criteriaMissing) {
    }

    private final ResultStore store;
    private final AccessControlService access;

    public CriteriaIngestService(ResultStore store, AccessControlService access) {
        this.store = store;
        this.access = access;
    }

    /** 判据明细（GET /criteria 用），结论由 {@link CaseConclusionResolver} 统一裁定。 */
    public List<CriteriaRow> list(long projectId, String runId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        return store.listCriteria(projectId, runId);
    }
}
