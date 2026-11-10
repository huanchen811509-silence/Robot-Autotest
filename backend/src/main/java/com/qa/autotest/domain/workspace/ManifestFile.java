package com.qa.autotest.domain.workspace;

/** 工作区包内单个文件条目（manifest.files[]）。 */
public record ManifestFile(String path, String sha256, long bytes) {
}
