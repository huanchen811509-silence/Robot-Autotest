package com.qa.autotest.application.report;

import java.util.ArrayList;
import java.util.List;

/**
 * 报告 8 章内容模型（FR-v5.4-02 / D4–D6 / W54-02）。
 *
 * <p>八章顺序固定，且**每章只有一个「有无数据」判定，没有「省略」这个选项**
 * （R1/R2）：数据缺失时章仍在，正文写「无数据」并给出原因。
 * 这条规则看起来啰嗦，但它挡住的是报告里最常见也最难发现的问题——
 * 一章因为没数据被跳过，读者会以为「这次没有失败」，而事实可能是「失败分析没跑」。
 *
 * <p><b>第 1 章判定建议由规则推导，不由 AI 生成</b>（R3）。理由：判定建议会被当作
 * 「能不能发版」的依据，而模型输出不可复算、不可审计。规则推导的每一条都能指回
 * 具体的计数，谁不同意都能拿数据反驳。
 *
 * <p><b>第 8 章三条声明是硬要求</b>：覆盖率≠完成度、PLANNED 不计通过率、
 * AI 建议不参与判定。它们出现在报告末尾而不是开头，是因为读者先看结论、
 * 再看口径；但必须存在——没有口径声明的通过率，过三个月就没人说得清它算的是什么。
 */
public final class ReportChapterBuilder {

    /** 章状态：有数据 / 无数据 / 必填缺失（只有第 1、8 章可能是必填缺失）。 */
    public enum Status {
        /** 有数据，正文可渲染。 */
        OK,
        /** 数据源为空，但章仍在，正文标「无数据」+ 原因。 */
        NO_DATA,
        /** 必填章缺数据——这是报告本身不完整，不是「这次没内容」。 */
        MISSING
    }

    /**
     * 一章。
     *
     * @param no         章号（1..8）
     * @param title      章标题
     * @param status     数据状态
     * @param dataSource 数据来源（可展开的追溯入口，R2）
     * @param summary    一句话摘要（结论章就是那句结论）
     * @param missing    缺失时给读者的说明；{@code status=OK} 时为 null
     * @param items      明细行（键值对，保持插入顺序）
     */
    public record Chapter(int no, String title, Status status, String dataSource,
                          String summary, String missing, List<Item> items) {

        public boolean ok() {
            return status == Status.OK;
        }

        /** 页面是否要显示「无数据」灰块。 */
        public boolean needsPlaceholder() {
            return status != Status.OK;
        }
    }

    /** 明细行。 */
    public record Item(String label, String value) {
    }

    /** 第 8 章必须包含的三条口径声明（逐字固定，不得改写）。 */
    public static final List<String> DECLARATIONS = List.of(
            "覆盖率 ≠ 完成度：覆盖率统计的是「有用例库条目」的比例，不代表功能已验证完整",
            "PLANNED 用例不计入通过率：未实现/未执行用例既不拉高也不拉低通过率，单独计数",
            "AI 建议不参与判定：任何 AI 生成内容仅供参考，判定依据只有用例结论与规则");

    /** 章标题（固定顺序，不得调整）。 */
    public static final List<String> TITLES = List.of(
            "结论摘要", "执行环境", "结果分布", "失败分析",
            "时间分析", "覆盖影响", "命令集合摘要", "附录与口径");

    private ReportChapterBuilder() {
    }

    /**
     * 判定建议（第 1 章，纯规则）。
     *
     * @param failed     FAIL 用例数
     * @param total      参与判定的用例数（不含 PLANNED）
     * @param planned    PLANNED 用例数
     * @param passRate   通过率（百分比）
     */
    public static String verdictAdvice(int failed, int total, int planned, double passRate) {
        if (total <= 0) {
            return "不建议通过：本次没有任何参与判定的用例结果";
        }
        if (failed > 0) {
            return "不建议通过：存在 " + failed + " 条失败用例，需先定位或按已知问题处置";
        }
        if (planned > 0) {
            return "建议复验：无失败，但有 " + planned + " 条计划用例未执行（通过率 "
                    + passRate + "% 只覆盖已执行部分）";
        }
        return "建议通过：全部 " + total + " 条用例通过，无计划外缺口";
    }

    /**
     * 组装八章。
     *
     * <p>入参刻意做成「八份可选数据」：调用方从不同服务取数，任何一份取不到就传 null，
     * 由本方法统一降级成「无数据」。这样「取数失败」与「没有数据」在报告里长得一样，
     * 都是如实标注——而不是让报告渲染整体失败。
     *
     * @param summary   第 1 章（必填）
     * @param env       第 2 章（可空）
     * @param dist      第 3 章（可空）
     * @param failures  第 4 章（可空）
     * @param durations 第 5 章（可空）
     * @param coverage  第 6 章（可空）
     * @param commands  第 7 章（可空）
     * @param appendix  第 8 章（必填）
     */
    public static List<Chapter> build(Chapter summary, Chapter env, Chapter dist,
                                      Chapter failures, Chapter durations, Chapter coverage,
                                      Chapter commands, Chapter appendix) {
        List<Chapter> chapters = new ArrayList<>(8);
        chapters.add(required(1, summary));
        chapters.add(optional(2, env, "未记录执行环境（run_metadata 缺 commit/内核/模式）"));
        chapters.add(optional(3, dist, "无分类数据（用例未打分类/优先级标签）"));
        chapters.add(optional(4, failures, "无失败（本次执行没有 FAIL 用例）"));
        chapters.add(optional(5, durations, "基线未建立（样本不足 5 条或尚未重算）"));
        chapters.add(optional(6, coverage, "未统计（本次未做覆盖率计算）"));
        chapters.add(optional(7, commands, "未采集（本次执行未开启监视器）"));
        chapters.add(required(8, appendix));
        return List.copyOf(chapters);
    }

    /**
     * 第 1 章构造（结论摘要）。
     *
     * @param runId    执行编号
     * @param total    参与判定用例数
     * @param passed   通过数
     * @param failed   失败数
     * @param planned  计划用例数
     * @param passRate 通过率（百分比）
     */
    public static Chapter summary(String runId, int total, int passed, int failed, int planned,
                                  double passRate) {
        List<Item> items = new ArrayList<>();
        items.add(new Item("执行编号", runId));
        items.add(new Item("参与判定", total + " 条"));
        items.add(new Item("通过 / 失败", passed + " / " + failed));
        items.add(new Item("通过率", passRate + "%"));
        items.add(new Item("计划未执行", planned + " 条"));

        String advice = verdictAdvice(failed, total, planned, passRate);
        boolean failedInvariant = total <= 0;
        return new Chapter(1, TITLES.get(0),
                failedInvariant ? Status.MISSING : Status.OK,
                "test_report + test_result 聚合", advice,
                failedInvariant ? "本次执行没有参与判定的用例结果，无法给出结论" : null,
                List.copyOf(items));
    }

    /**
     * 第 8 章构造（附录与口径）：三条声明固定在内，不依赖调用方传入。
     *
     * @param evidenceIndex 证据索引（产物名 → 路径）
     * @param caseList      用例清单摘要（可为空列表）
     */
    public static Chapter appendix(List<Item> evidenceIndex, List<Item> caseList) {
        List<Item> items = new ArrayList<>();
        if (evidenceIndex != null) {
            items.addAll(evidenceIndex);
        }
        if (caseList != null) {
            items.addAll(caseList);
        }
        for (int i = 0; i < DECLARATIONS.size(); i++) {
            items.add(new Item("口径声明 " + (i + 1), DECLARATIONS.get(i)));
        }
        return new Chapter(8, TITLES.get(7), Status.OK, "run_artifact + test_result 清单",
                "共 " + DECLARATIONS.size() + " 条口径声明", null, List.copyOf(items));
    }

    private static Chapter required(int no, Chapter chapter) {
        if (chapter != null && chapter.ok()) {
            return chapter;
        }
        String reason = chapter == null ? "本章数据未提供" : chapter.missing();
        return new Chapter(no, TITLES.get(no - 1), Status.MISSING, "未取数",
                "必填章缺失", reason == null ? "必填章缺失" : reason, List.of());
    }

    private static Chapter optional(int no, Chapter chapter, String defaultMissing) {
        if (chapter != null && chapter.ok()) {
            return chapter;
        }
        String reason = chapter != null && chapter.missing() != null
                ? chapter.missing() : defaultMissing;
        return new Chapter(no, TITLES.get(no - 1), Status.NO_DATA,
                chapter == null ? "未取数" : chapter.dataSource(),
                "无数据", reason, List.of());
    }
}
