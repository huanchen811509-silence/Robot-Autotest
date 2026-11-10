package com.qa.autotest.domain.workspace;

import java.util.List;

/**
 * 工作区包清单（docs/contracts/workspace-bundle.md §3）。
 *
 * <p>{@code signature} 覆盖「去掉 signature 后的规范形 JSON」；
 * 规范形 = 键名字典序、无缩进、UTF-8，Python 机检与 Java 端必须一致。</p>
 */
public record WorkspaceManifest(
        int formatVersion,
        String projectCode,
        String exportedAt,
        String generator,
        String granularity,
        String rootPath,
        List<ManifestFile> files,
        Signature signature) {

    public record Signature(String algorithm, String keyId, String value) {
    }
}
