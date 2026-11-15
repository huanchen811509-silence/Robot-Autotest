package com.qa.autotest.application.repository;

import com.qa.autotest.domain.report.ReportTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 报告模板仓储（TPLX-01）。所有查询都带 {@code project_id} 条件（TenantGuard 口径）。
 *
 * <p>「可见」= 平台级（{@code project_id = 0}）+ 本项目。平台级模板是内置的只读模板，
 * 任何项目都能用、都不能改。
 */
public interface ReportTemplateRepository {

    /** 可见模板：平台级 + 本项目，平台级在前（它是默认值来源）。 */
    List<ReportTemplate> listVisible(long projectId);

    Optional<ReportTemplate> find(long projectId, long id);

    /** 按名称精确查（同一 scope 内唯一），用于唯一键冲突前的友好报错。 */
    Optional<ReportTemplate> findByName(long projectId, String name);

    /** 该模板是否属于**别的**项目，用于把跨项目访问判成 403 而不是 404。 */
    boolean existsInOtherProject(long projectId, long id);

    long insert(ReportTemplate draft, Long operatorId);

    /** 更新基本信息并把 version + 1（版本号供 TPLX-02 的快照回指）。 */
    void update(long projectId, long id, String name, String description, Long operatorId);

    void delete(long projectId, long id);

    /** 全量替换字段（先删后插）：模板字段是整体配置，逐条 diff 没有收益。 */
    void replaceFields(long projectId, long templateId, List<ReportTemplate.Field> fields,
                       Long operatorId);

    /** 项目的默认模板；没有项目级默认则回落到平台级，都没有则 empty。 */
    Optional<ReportTemplate> findDefault(long projectId);

    /**
     * v5.14.0 / W64-11：按该 run 的内核做分级默认模板选择。
     *
     * <p>用 {@code default} 方法回落旧口径，是为了不让所有既有伪仓储 / mock 被迫实现新方法：
     * 真实 JDBC 实现会覆盖它并按 kernel 分级，测试里的伪仓储仍走原来的两级回退。
     */
    default Optional<ReportTemplate> findDefaultForRun(long projectId, String runId) {
        return findDefault(projectId);
    }

    /**
     * v5.14.0 / W64-11：该 run 的内核（{@code run.kernel}）。报告详情要把「这次模板
     * 选择落在四级里的哪一级」透出给界面，需要这个事实；查不到按 robot 兜底，
     * 与迁移前行为一致。default 回落同样是为了不打破既有伪仓储。
     */
    default String runKernel(long projectId, String runId) {
        return ReportTemplate.KERNEL_ROBOT;
    }

    /**
     * 把某模板设为默认：只清**本项目**的默认标记再置位，平台级（{@code project_id = 0}）的
     * 默认标记不动——它是所有项目的全局回落值，不能被单个项目的操作顺手清掉。
     */
    void markDefault(long projectId, long id);
}
