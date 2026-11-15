package com.qa.autotest.application.repository;

import java.util.List;

/** 环境闸门留痕仓储（v5.15.0 / W65-17）。 */
public interface EnvironmentGateRepository {

    record RunNote(String runId, String note) {
    }

    /** 只填空不覆盖：已有留痕的 run 不再被改写。返回实际写入行数。 */
    int noteRun(long projectId, String runId, String note);

    List<RunNote> notes(long projectId, int limit);
}
