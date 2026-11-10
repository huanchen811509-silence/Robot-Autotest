package com.qa.autotest.domain.workspace;

import java.util.List;

/**
 * 工作区导入预检/落库报告（dry-run 与真实导入共用同一结构）。
 *
 * @param ok           整包是否可导入（签名/格式/路径/配额失败时为 false）
 * @param items        逐条结果：category=FILE/CASE/CASE_SET/MEMBER/ASSET/CREDENTIAL/ENV/REPORT
 *                     action=IMPORT/SKIP/OVERWRITE/RENAME/QUARANTINE/ARCHIVE
 * @param quarantine   命中的可疑脚本（落隔离区，默认不写库）
 * @param warnings     非阻断提示（例如成员/资产缺失、归档只读）
 */
public record WorkspaceReport(
        boolean ok,
        String bundlePath,
        String errorCode,
        String message,
        int filesImported,
        int filesSkipped,
        int filesQuarantined,
        List<Item> items,
        List<Quarantine> quarantine,
        List<String> warnings) {

    public record Item(String category, String key, String action, String reason) {
    }

    public record Quarantine(String path, List<String> findings) {
    }
}
