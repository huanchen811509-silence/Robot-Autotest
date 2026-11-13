package com.qa.autotest.application.repository;

import com.qa.autotest.domain.host.HostInventoryRecord;
import java.util.List;
import java.util.Optional;

/** 主机清单仓储（FR-v3.11-01）。只追加不更新：一次采集就是一个历史时点，改它等于毁证据。 */
public interface HostInventoryRepository {

    long insert(HostInventoryRecord record, Long operatorId);

    Optional<HostInventoryRecord> find(long projectId, long id);

    /**
     * 最近若干次采集，按时间倒序。
     *
     * @param deviceId 为空表示该项目下所有设备
     */
    List<HostInventoryRecord> list(long projectId, Long deviceId, int limit);

    /**
     * 最近一次采集的**完整**记录（含 {@code spec_json}）。没有采集过则返回空。
     *
     * <p>为什么不能拿 {@link #list} 的第一条凑：{@code list} 故意只查摘要列，
     * {@code specJson} 给的是 null（见 {@code HostInventoryRepositoryJdbc.map} 的注释）。
     * 依赖「渲染投影」的调用方要的是 GPU/CPU/存储明细，这些明细只存在于
     * {@code spec_json} 里，用摘要行会得到一个「明明采过却什么也没有」的空结果。
     */
    Optional<HostInventoryRecord> latest(long projectId, Long deviceId);
}
