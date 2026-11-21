package com.qa.autotest.common;

import java.util.List;
import java.util.Map;

/**
 * 安全级拦截（ADR-0013 / G2-3）的结构化形态。
 *
 * <p>原先只抛 {@link BusinessException}，403 的错误体里只有一段拼好的中文，前端拿不到
 * 「到底哪几条、各是什么安全级」——用户只能自己去用例列表里翻。这里把受阻清单作为
 * {@code data.blocked} 一并返回，页面可以直接渲染成可点条目（FR-UIE-10）。
 *
 * <p>错误码与状态不变（{@code 40301} / 403），既有调用方与测试不受影响。
 */
public class SafetyBlockedException extends BusinessException {

    private final transient List<Map<String, String>> blocked;

    public SafetyBlockedException(String message, List<Map<String, String>> blocked) {
        super(ErrorCode.SAFETY_LEVEL_BLOCKED, message);
        this.blocked = blocked == null ? List.of() : List.copyOf(blocked);
    }

    public List<Map<String, String>> blocked() {
        return blocked;
    }
}
