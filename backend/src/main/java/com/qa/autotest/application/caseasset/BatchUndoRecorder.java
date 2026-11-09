package com.qa.autotest.application.caseasset;

import com.qa.autotest.domain.caseasset.ReviewSnapshot;

import java.util.List;

/**
 * 批量撤销凭据写入（FR-3.1-04）。
 *
 * <p>只有可逆动作才调用；TestCaseService 在批量成功后调用，
 * 撤销服务不可用时（单测/旧装配）允许为 null，批量本身不受影响。
 */
public interface BatchUndoRecorder {

    /** @return 撤销 token；没有快照时返回 null。 */
    String record(long projectId, String action, List<ReviewSnapshot> snapshots);
}
