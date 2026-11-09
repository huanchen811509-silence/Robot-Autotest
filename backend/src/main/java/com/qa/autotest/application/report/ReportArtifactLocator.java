package com.qa.autotest.application.report;

import java.nio.file.Path;
import java.util.Optional;

/**
 * output.xml 定位端口（RPT-02）。生产实现是 {@link ReportFileLocator}，
 * 测试可注入轻量实现，不必拖起运行/产物仓储。
 */
public interface ReportArtifactLocator {

    Path locateOutputXml(long projectId, String runId);

    /**
     * 用例日志文件定位（v3.9.0 / FR-v3.9-03）。
     *
     * <p>两种内核日志源不同：RF 直接读 output.xml；pytest 读入库时生成的
     * {@code case-log.jsonl}。默认实现退回 output.xml，保持既有测试替身可用。
     */
    default Optional<Path> locateCaseLog(long projectId, String runId) {
        return Optional.of(locateOutputXml(projectId, runId));
    }
}
