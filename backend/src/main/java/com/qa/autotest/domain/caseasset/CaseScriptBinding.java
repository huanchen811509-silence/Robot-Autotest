package com.qa.autotest.domain.caseasset;

/**
 * 用例 ↔ 脚本绑定（V11_47 / FR-v5.1-04 / ADR-51-02）。
 *
 * <p>存在的唯一理由：junit 的点分 classname 与 json-report 的斜杠 nodeid 不同源，
 * 而对外要显示的是用例库编号。绑定关系显式落库，**不从 nodeid 反解编号**。
 *
 * @param resolveSource FILENAME / DOCSTRING / MANUAL，记录这条绑定是怎么来的
 * @param verified      是否经人工确认（MANUAL 天然为真，其余需复核）
 */
public record CaseScriptBinding(
        Long id,
        Long projectId,
        Long caseId,
        String kernel,
        String scriptPath,
        String nodeId,
        String scriptSha256,
        String resolveSource,
        boolean verified) {

    /** 解析来源：文件名反推（降级手段）。 */
    public static final String SOURCE_FILENAME = "FILENAME";
    /** 解析来源：文件头 `CASE_ID:` 声明（唯一可信）。 */
    public static final String SOURCE_DOCSTRING = "DOCSTRING";
    /** 解析来源：人工指定。 */
    public static final String SOURCE_MANUAL = "MANUAL";

    public CaseScriptBinding {
        if (kernel == null || kernel.isBlank()) {
            kernel = "pytest";
        }
        if (resolveSource == null || resolveSource.isBlank()) {
            resolveSource = SOURCE_FILENAME;
        }
    }
}
