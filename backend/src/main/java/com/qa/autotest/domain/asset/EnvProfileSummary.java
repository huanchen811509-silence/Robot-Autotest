package com.qa.autotest.domain.asset;

import java.time.Instant;

/**
 * 一个环境的画像摘要（FR-v3.11-02）。
 *
 * <p>`env_profile` 是「一次快照一行」，同一个环境名会有很多行；列表页要先知道
 * **有哪些环境**才能按名查快照，否则 `GET /snapshots?name=` 只能靠用户手敲名字。
 * 这个对象只做「名字 → 最近一次快照」的索引，不复制快照内容。
 *
 * @param name          环境名（同一项目内唯一，快照按它分组）
 * @param profileType   最近一次快照的类型
 * @param snapshotCount 该环境的快照条数
 * @param latestAt      最近一次快照时间；无快照时为 null（不拿 1970 冒充时间）
 */
public record EnvProfileSummary(String name,
                                String profileType,
                                int snapshotCount,
                                Instant latestAt) {
}
