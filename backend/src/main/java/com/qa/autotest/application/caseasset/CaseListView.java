package com.qa.autotest.application.caseasset;

import com.qa.autotest.domain.caseasset.TestCase;

/**
 * 用例列表行（v5.11.0 / W61-11）：{@link TestCase} 的全部对外字段 + 脚本三件套。
 *
 * <p><b>为什么不直接给 {@code TestCase} 加字段</b>：那是领域记录，全仓 21 处构造点，
 * 加字段等于把「显示用的脚本路径」塞进领域模型，还要改一堆不关心脚本的构造点。
 * 列表响应本来就是「给界面看的投影」，投影就该独立成记录。
 *
 * <p>字段名与 {@code TestCase} 逐一对应（JSON 键不变），因此老前端不解码新字段也能正常工作。
 */
public record CaseListView(long id, long projectId, String caseCode, String title,
                           String category, String priority, String precondition,
                           String expected, String domainTag, String reviewStatus,
                           String source, String capability, String description,
                           String safetyLevel,
                           String scriptPath, String scriptName, String scriptNodeId,
                           String bindingState, String scriptKernel, int otherScriptCount,
                           boolean scriptAbsoluteSource) {

    public static CaseListView of(TestCase testCase, CaseScriptView script) {
        CaseScriptView view = script == null ? CaseScriptView.empty(null) : script;
        return new CaseListView(
                testCase.id() == null ? 0L : testCase.id(),
                testCase.projectId() == null ? 0L : testCase.projectId(),
                testCase.caseCode(), testCase.title(), testCase.category(), testCase.priority(),
                testCase.precondition(), testCase.expected(), testCase.domainTag(),
                testCase.reviewStatus(), testCase.source(), testCase.capability(),
                testCase.description(), testCase.safetyLevel(),
                view.scriptPath(), view.scriptName(), view.nodeId(), view.bindingState(),
                view.kernel(), view.otherScriptCount(), view.absoluteSource());
    }

    /** 无脚本解析服务时（老装配 / 单测）的纯投影：脚本字段全部为「不适用」。 */
    public static CaseListView of(TestCase testCase) {
        return of(testCase, null);
    }
}
