package com.qa.autotest.application.report;

import com.qa.autotest.domain.report.ReportTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * C5 对外报告字段白名单（{@code docs/plan/C5-对外报告字段白名单-20261106.md} §2）——**唯一真源**。
 *
 * <p>TPLX-01 把它从 {@link ReportViewRenderer} 里搬出来：原来常量埋在渲染器内部，
 * 报告模板要给用户「勾选哪些字段」，就必须有一份**可被接口和界面引用**的清单。
 * 三处（渲染器 / 模板接口 / 前端勾选项）各写一份，迟早会漂移，而漂移的后果是
 * 「模板里能选的字段，导出时被渲染器判成非法」或者反过来「白名单已收紧，模板还能选」。
 *
 * <p>两条口径写死在这里，改之前先改文档：
 * <ol>
 *   <li><b>白名单不是黑名单</b>：未列字段默认拒绝，未来新增字段不会自动流出去；</li>
 *   <li><b>必填字段不可从模板里删</b>（{@link #REQUIRED_FIELDS}）：
 *       水印与免责声明是对外文件的合规底线，允许「模板去掉水印」等于给了一条绕过 C5 的路。</li>
 * </ol>
 */
public final class ReportFieldWhitelist {

    /**
     * 顺序 = 默认渲染顺序，也等于接口返回给前端的展示顺序。
     * 改顺序等于改导出文件的字段顺序，必须同步改文档 §2 并留修订记录。
     */
    public static final List<String> EXTERNAL_ORDER = List.of(
            "reportId", "title", "version", "frozenAt", "conclusion",
            "passCount", "failCount", "skipCount", "passRate", "caseTotal",
            "caseCovered", "coverageRate", "failureDistribution", "trend",
            "projectName", "swBuild", "caseKind", "startedAt", "finishedAt",
            "disclaimer", "watermark");

    /**
     * v5.14.0 / W64-12：pytest 专属字段。
     *
     * <p>刻意**不并入** {@link #EXTERNAL_ORDER}：那份列表同时是默认渲染顺序与平台
     * DEFAULT 模板的字段集，加一项就会让所有标准 RF 报告的导出多一列（判据 2 要求
     * 「行为不变、字段数不变」）。这里只扩「允许集合」，顺序仍由模板自己决定。
     */
    public static final List<String> PYTEST_ORDER = List.of(
            "criteriaConclusion", "nodeId", "paramId", "junitMessage", "artifactPath");

    /** 允许出对外视图的全部字段 = 通用 21 项 + pytest 专属 5 项，仍是唯一真源。 */
    public static final Set<String> EXTERNAL_FIELDS = Set.copyOf(
            Stream.concat(EXTERNAL_ORDER.stream(), PYTEST_ORDER.stream()).toList());

    /**
     * 界面可选字段按内核分组：robot / any 给通用 21 项，pytest 额外给 5 项专属字段。
     * 未知内核不给任何扩展（默认拒绝），避免「随便传个 kernel 就放开字段」。
     */
    public static List<String> selectable(String kernel) {
        if (ReportTemplate.KERNEL_PYTEST.equalsIgnoreCase(kernel)) {
            return List.copyOf(Stream.concat(EXTERNAL_ORDER.stream(),
                    PYTEST_ORDER.stream()).toList());
        }
        return EXTERNAL_ORDER;
    }

    /**
     * 模板里不可删除的字段。
     *
     * <p>`reportId/title/version/frozenAt`：没有它们这份文件无法追溯到某次冻结，对外报告就没有意义；
     * `disclaimer/watermark`：合规底线，见类注释。
     */
    public static final Set<String> REQUIRED_FIELDS = Set.of(
            "reportId", "title", "version", "frozenAt", "disclaimer", "watermark");

    /** metadata 只允许这五键进入对外视图（与 {@code MetadataGate} 的十项不是一回事）。 */
    public static final List<String> METADATA_ORDER = List.of(
            "SW_BUILD", "CASE_KIND", "QA_PROJECT", "DUT", "LINK");

    public static final Set<String> METADATA_FIELDS = Set.copyOf(METADATA_ORDER);

    private ReportFieldWhitelist() {
    }

    public static boolean allows(String fieldKey) {
        return fieldKey != null && EXTERNAL_FIELDS.contains(fieldKey);
    }

    public static boolean isRequired(String fieldKey) {
        return fieldKey != null && REQUIRED_FIELDS.contains(fieldKey);
    }

    /**
     * 挑出不在白名单里的字段，**保持入参顺序**：报错文案要能直接念给调用方听，
     * 排过序会让「用户填的第三个字段不合法」变成「某个字段不合法」。
     */
    public static List<String> illegal(Collection<String> fieldKeys) {
        List<String> bad = new ArrayList<>();
        if (fieldKeys == null) {
            return bad;
        }
        for (String key : fieldKeys) {
            if (!allows(key)) {
                bad.add(key);
            }
        }
        return bad;
    }

    /** 去重后按出现顺序返回，用于「重复字段」检查的同时不改变用户提交的顺序。 */
    public static List<String> distinct(Collection<String> fieldKeys) {
        return fieldKeys == null ? List.of() : List.copyOf(new LinkedHashSet<>(fieldKeys));
    }
}
