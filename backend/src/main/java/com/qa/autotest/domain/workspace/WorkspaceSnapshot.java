package com.qa.autotest.domain.workspace;

import java.util.List;

/**
 * 项目工作区快照：一次只读事务内聚合出的全部可导出数据。
 *
 * <p>字段名即包内 JSON 的键名（契约 §4），改名等于改契约，必须先改
 * {@code docs/contracts/workspace-bundle.md} 与格式版本号。</p>
 */
public record WorkspaceSnapshot(
        ProjectInfo project,
        List<MemberInfo> members,
        List<CaseInfo> cases,
        List<CaseSetInfo> caseSets,
        List<AssetInfo> assets,
        List<CredentialInfo> credentials,
        List<EnvironmentInfo> environments,
        List<ReportInfo> reports,
        List<RunInfo> runs,
        List<FileInfo> files) {

    public record ProjectInfo(String code, String name, String description, String testKernel) {
    }

    public record MemberInfo(String login, String displayName, String role) {
    }

    public record CaseInfo(String caseCode, String title, String kind, String sourceType,
                           String priority, String domainTag, String description,
                           List<StepInfo> steps) {
    }

    public record StepInfo(int stepNo, String action, String expectation) {
    }

    public record CaseSetInfo(String setCode, String name, String description, List<SetItem> items) {
    }

    public record SetItem(String caseCode, int seqNo) {
    }

    /** 设备与链路统一为资产引用：type = DEVICE / LINK。 */
    public record AssetInfo(String name, String type, String address) {
    }

    /** 凭据只出名称与掩码指纹；密文/密钥引用永不出包（ADR-4X-13）。 */
    public record CredentialInfo(String name, String credType, String username, String mask) {
    }

    public record EnvironmentInfo(String name, String profileType, String description) {
    }

    public record ReportInfo(String reportNo, String runId, String title, String state, String createdAt) {
    }

    public record RunInfo(String runId, String testSetCode, String status, String startedAt) {
    }

    public record FileInfo(String path, String content) {
    }
}
