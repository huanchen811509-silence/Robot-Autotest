package com.qa.autotest.domain.ops;

/** 告警通道（FR-3.3-09）。{@code secret} 只在服务层用于签名，绝不出现在响应体。 */
public record AlertChannel(long id, long projectId, String kind, String target,
                           boolean enabled, boolean hasSecret) {
}
