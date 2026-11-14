package com.qa.autotest.application.evidence;

import java.util.Optional;

/**
 * 证据包指针表的**应用层端口**（v5.7.0 / W57-24）。
 *
 * <p>接口放在 application 包、实现放在 infrastructure 包，是为了守住分层单向依赖：
 * application 不能反向引用 infrastructure 的具体类（LayerRules 的「分层无环」会判红）。
 * DB 这行只存指针摘要与状态，磁盘 pointer 文件才是文件清单的唯一真源。
 */
public interface EvidenceBundleStore {

    void begin(String bundleKey, Long projectId);

    void markReady(String bundleKey, String pointerSha, int fileCount, long totalBytes);

    void markFailed(String bundleKey);

    Optional<BundleRow> find(String bundleKey);

    record BundleRow(String bundleKey, String pointerSha, int fileCount,
                     long totalBytes, String status) {
    }
}
