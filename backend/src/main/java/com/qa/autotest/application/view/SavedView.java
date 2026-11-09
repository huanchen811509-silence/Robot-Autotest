package com.qa.autotest.application.view;

import java.time.Instant;

/**
 * 用户保存的视图（FR-3.1-03）。
 *
 * <p>payload 保持为原始 JSON 对象：前端把筛选条件原样存进来，
 * 后端不解释字段含义，只负责隔离、去重与容量限制。
 */
public record SavedView(long id, long projectId, long userId, String name, String scope,
                        Object payload, boolean isDefault, Instant createdAt, Instant updatedAt) {
}
