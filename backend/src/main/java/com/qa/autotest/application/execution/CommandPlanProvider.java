package com.qa.autotest.application.execution;

import java.util.List;

/**
 * 命令清单生成器（W57-18）：按执行内核把「用例 id 清单」翻译成可执行命令清单。
 *
 * <p>硬约束：读不到可信绑定时<b>返回空清单</b>，由调用方标注跳过原因，
 * 绝不允许编造 argv / cwd。实现类必须是无状态的 Spring 单例（不得缓存上一次结果）。
 */
public interface CommandPlanProvider {

    /** 内核标识：robot / pytest，与 {@code case_script_binding.kernel} 同源。 */
    String kernel();

    /**
     * 生成命令清单。返回的每条都是能可靠解析到的命令；无法解析的用例不出现在结果里。
     *
     * @param projectId 项目作用域（所有查询必须带此条件）
     * @param caseIds   用例台账 id
     */
    List<CommandPlan> plans(long projectId, List<Long> caseIds);
}
