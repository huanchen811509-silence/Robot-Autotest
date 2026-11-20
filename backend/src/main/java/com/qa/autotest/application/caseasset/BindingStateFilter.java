package com.qa.autotest.application.caseasset;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用例「脚本绑定状态」过滤条件（FR-READY-01/02/10/11）。
 *
 * <p><b>单一真源</b>：四态字符串与 {@link CaseScriptView} 完全同源，任何列表/预检/组集判定
 * 都调用本类，不在前端或 Controller 各自拼装（FR-v5.11-05 判据 6：三处逐字相同）。
 *
 * <p><b>分页前过滤</b>：本类只生成 SQL 片段（EXISTS 子查询），由仓储在分页前拼进 WHERE，
 * 保证 {@code total} 与结果同源；禁止分页后在内存里筛。
 *
 * <p><b>内核语义</b>：只有 {@code pytest} 内核写 {@code case_script_binding}。
 * robot（含 platform 公共库）内核下：请求 UNBOUND → 恒 0 条；请求 NOT_APPLICABLE → 全部。
 * 这是 {@link CaseScriptView#empty(String)} 的 SQL 等价物，二者口径必须一致。
 */
public final class BindingStateFilter {

    public static final String BOUND_VERIFIED = CaseScriptView.BOUND_VERIFIED;
    public static final String BOUND_UNVERIFIED = CaseScriptView.BOUND_UNVERIFIED;
    public static final String UNBOUND = CaseScriptView.UNBOUND;
    public static final String NOT_APPLICABLE = CaseScriptView.NOT_APPLICABLE;

    private static final Set<String> ALLOWED = Set.of(
            BOUND_VERIFIED, BOUND_UNVERIFIED, UNBOUND, NOT_APPLICABLE);

    private BindingStateFilter() {
    }

    /** 四态白名单；null/空视为不过滤（合法）。 */
    public static boolean isValid(String state) {
        return state == null || state.isBlank() || ALLOWED.contains(state.trim());
    }

    /**
     * 把 bindingState 过滤拼进 WHERE（分页前）。
     *
     * <p>调用方需保证 SELECT 中用例 id 列可用限定名（如 {@code tc.id}），避免 EXISTS 子查询
     * 里出现歧义列。所有参数按追加顺序写入 {@code args}，与 SQL 片段一一对应。
     *
     * @param where        已有 WHERE（形如 " WHERE ..."，可继续 " AND ..."）
     * @param args         SQL 参数列表，本方法按需追加
     * @param projectId    项目 id（platform 单例或 pytest-suite 项目）
     * @param kernel       项目内核（{@link CaseScriptLookup#kernelOf(long)}）
     * @param state        四态之一或 null/空（不过滤）
     * @param caseIdColumn 用例 id 限定列名（如 {@code tc.id}）
     */
    public static void apply(StringBuilder where, List<Object> args,
                             long projectId, String kernel, String state, String caseIdColumn) {
        if (state == null || state.isBlank()) {
            return;
        }
        String s = state.trim();
        if (!ALLOWED.contains(s)) {
            throw new IllegalArgumentException("非法 bindingState: " + state);
        }
        boolean supportsScript = CaseScriptView.supportsScript(kernel);
        if (!supportsScript) {
            // 不支持脚本内核：与 CaseScriptView.empty(kernel) 口径一致
            // NOT_APPLICABLE → 全部；UNBOUND / BOUND_* → 恒 0
            where.append(NOT_APPLICABLE.equals(s) ? " AND 1 = 1" : " AND 1 = 0");
            return;
        }
        if (NOT_APPLICABLE.equals(s)) {
            where.append(" AND 1 = 0");
            return;
        }
        String k = kernel.toLowerCase(Locale.ROOT);
        String prefix = " AND EXISTS (SELECT 1 FROM case_script_binding b"
                + " WHERE b.case_id = " + caseIdColumn
                + " AND b.project_id = ? AND b.kernel = ?";
        switch (s) {
            case BOUND_VERIFIED -> {
                where.append(prefix).append(" AND b.verified = 1)");
                args.add(projectId);
                args.add(k);
            }
            case BOUND_UNVERIFIED -> {
                where.append(prefix).append(" AND b.verified = 0)");
                args.add(projectId);
                args.add(k);
            }
            case UNBOUND -> {
                where.append(" AND NOT EXISTS (SELECT 1 FROM case_script_binding b"
                        + " WHERE b.case_id = ").append(caseIdColumn)
                        .append(" AND b.project_id = ? AND b.kernel = ?)");
                args.add(projectId);
                args.add(k);
            }
            default -> throw new IllegalArgumentException("非法 bindingState: " + state);
        }
    }

    /** InstanceState：把内核不支持时的可见状态归一为 NOT_APPLICABLE（供预检/展示复用）。 */
    public static String effectiveState(String kernel, String rawState) {
        if (!CaseScriptView.supportsScript(kernel)) {
            return NOT_APPLICABLE;
        }
        return rawState == null ? UNBOUND : rawState;
    }
}
