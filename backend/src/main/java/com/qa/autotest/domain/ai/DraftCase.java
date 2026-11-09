package com.qa.autotest.domain.ai;

/** 一条用例草稿（FR-3.3-06）。source 是证据编号，保存前必须由人工逐条确认。 */
public record DraftCase(String title, String precondition, String steps, String expected,
                        String source) {
}
