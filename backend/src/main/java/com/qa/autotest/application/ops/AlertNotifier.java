package com.qa.autotest.application.ops;

import com.qa.autotest.domain.ops.AlertChannel;
import java.util.Map;

/** 告警投递抽象（FR-3.3-09）。通道故障必须返回可读状态，不允许静默丢弃事件。 */
public interface AlertNotifier {

    /** 投递结果：SENT / FAILED / DEGRADED，error 为可读原因。 */
    record Result(String status, String error) {
    }

    Result send(AlertChannel channel, String secret, Map<String, Object> payload);
}
