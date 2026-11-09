package com.qa.autotest.common.query;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.util.Map;
import java.util.Set;

/**
 * 统一分页参数（FR-3.2-02 / ADR-3X-04）。
 *
 * <p>三件事只在这里做一次：
 * <ul>
 *   <li><b>归一化</b>：page &lt; 1 → 1；size &lt; 1 → 默认 20；size &gt; 100 → 100。
 *       各仓库不再各写一套 {@code Math.min(size, 200)}。</li>
 *   <li><b>排序白名单</b>：只接受调用方显式登记的字段名，映射成固定 SQL 列；
 *       没登记就抛 {@link ErrorCode#INVALID_ARGUMENT}，绝不把请求参数拼进 SQL。</li>
 *   <li><b>偏移计算</b>：{@link #offset()} 只在这里算，避免各处 {@code (page-1)*size}
 *       抄错。</li>
 * </ul>
 *
 * <p>格式：{@code sort=updated_at}、{@code sort=updated_at,desc}、{@code sort=-updated_at}。
 * 不带 sort 时用调用方给的默认列与方向。
 */
public final class PageQuery {

    public static final int MAX_SIZE = 100;
    public static final int DEFAULT_SIZE = 20;

    private final int page;
    private final int size;
    private final String orderBy;
    private final boolean explicitSort;
    private final Long beforeId;

    private PageQuery(int page, int size, String orderBy, boolean explicitSort, Long beforeId) {
        this.page = page;
        this.size = size;
        this.orderBy = orderBy;
        this.explicitSort = explicitSort;
        this.beforeId = beforeId;
    }

    /** 默认分页：ORDER BY id DESC，兼容既有列表。 */
    public static PageQuery of(int page, int size) {
        return of(page, size, null, Map.of("id", "id"), "id");
    }

    /**
     * 键集分页（深翻页）：按 id DESC 取 {@code id < beforeId} 的下一页。
     *
     * <p>只对「按 id 倒序」的列表有效；调用方不得同时传页码语义。
     * 页大小仍受 {@link #MAX_SIZE} 约束。
     */
    public static PageQuery keyset(int size, long beforeId) {
        int normalizedSize = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new PageQuery(1, normalizedSize, "id DESC", true, beforeId);
    }

    /**
     * @param allowedColumns 白名单：请求字段名 → 实际 SQL 列（列内容由服务端登记，不接受外部输入）
     * @param defaultColumn  默认排序 SQL 列（必须来自服务端常量）
     */
    public static PageQuery of(int page, int size, String sort,
                               Map<String, String> allowedColumns, String defaultColumn) {
        if (allowedColumns == null || allowedColumns.isEmpty()) {
            throw new IllegalArgumentException("排序白名单不能为空");
        }
        if (!allowedColumns.containsValue(defaultColumn)) {
            throw new IllegalArgumentException("默认排序列必须属于白名单：" + defaultColumn);
        }
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);

        String column = defaultColumn;
        boolean descending = true;
        boolean explicit = sort != null && !sort.isBlank();
        if (explicit) {
            String value = sort.trim();
            boolean desc = true;
            String key;
            if (value.startsWith("-")) {
                key = value.substring(1).trim();
            } else if (value.startsWith("+")) {
                key = value.substring(1).trim();
                desc = false;
            } else {
                key = value;
            }
            int comma = key.indexOf(',');
            if (comma >= 0) {
                String dir = key.substring(comma + 1).trim().toLowerCase(java.util.Locale.ROOT);
                key = key.substring(0, comma).trim();
                if ("asc".equals(dir)) {
                    desc = false;
                } else if ("desc".equals(dir)) {
                    desc = true;
                } else {
                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                            "排序方向只支持 asc / desc：" + sort);
                }
            }
            String resolved = allowedColumns.get(key);
            if (resolved == null) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "不支持的排序字段：" + key + "，可用：" + String.join(",", allowedKeys(allowedColumns)));
            }
            column = resolved;
            descending = desc;
        }
        return new PageQuery(normalizedPage, normalizedSize,
                column + (descending ? " DESC" : " ASC"), explicit, null);
    }

    private static Set<String> allowedKeys(Map<String, String> allowedColumns) {
        return allowedColumns.keySet();
    }

    public int page() {
        return page;
    }

    public int size() {
        return size;
    }

    /** 已校验的 ORDER BY 片段（不含 "ORDER BY" 关键字）。 */
    public String orderBy() {
        return orderBy;
    }

    /** 调用方是否显式指定了排序；没有时仓储可保留自己的多列默认顺序（含 id 兜底）。 */
    public boolean explicitSort() {
        return explicitSort;
    }

    /** 键集游标（id）；为空表示页码模式。 */
    public Long beforeId() {
        return beforeId;
    }

    public boolean keysetMode() {
        return beforeId != null;
    }

    public int offset() {
        return keysetMode() ? 0 : (page - 1) * size;
    }
}
