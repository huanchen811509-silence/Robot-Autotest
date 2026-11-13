package com.qa.autotest.application.execution;

import java.util.List;

/**
 * 失败聚类视图（ORCH-03，v2.1）。
 *
 * <p>两级结构：**一级是失败类别**（复用既有 {@code FailureClassifier}：CANCELED &gt; ENV &gt;
 * INFRA &gt; ASSERT），**二级是归一化簇键**（{@code FailureClusterKey}）。
 * 只做二级不分类会把「环境挂了」和「断言写错」混在一张表里，用户分不清该修环境还是修用例。
 *
 * @param total 本轮失败用例总数（含无 caseId 的行）
 * @param classes 按枚举声明序排列（固定顺序，不按数量重排——否则两次刷新顺序会变）
 */
public record FailureClusterView(String runId,
                                 int total,
                                 String runStatus,
                                 String runFailureClass,
                                 List<ClassGroup> classes) {

    public FailureClusterView {
        classes = classes == null ? List.of() : List.copyOf(classes);
    }

    /** 一级分组：同一失败类别的所有簇。 */
    public record ClassGroup(String failureClass,
                             int count,
                             double ratio,
                             List<Cluster> clusters) {

        public ClassGroup {
            clusters = clusters == null ? List.of() : List.copyOf(clusters);
        }
    }

    /**
     * 二级簇。
     *
     * <p><b>键与展示名分离（v5.6.0 / FR-v5.6-22）</b>：{@code key} 是归一化指纹，
     * 长文本会被截成「33 字符前缀 + … + 4 位哈希」，直接给用户看等于让人读乱码；
     * {@code display} 取该簇首个失败样本的首行原文，不参与聚合、不脱敏、不截断到指纹长度。
     * 两者必须同时返回：前端列表用 {@code display} 展示，重跑/去重仍用 {@code key}。
     *
     * @param key      归一化簇键（同一输入必得同一键）——聚合与重跑用它，不可用于展示
     * @param display  可读原文（首个样本失败消息首行）；无原文时回落到 {@code key}
     * @param samples  最多 3 条样例用例，供用户判断「这是不是同一回事」
     * @param caseIds  该簇全部用例 id，重跑清单直接用它（不截断）
     */
    public record Cluster(String key,
                          String display,
                          int count,
                          double ratio,
                          List<Sample> samples,
                          List<Long> caseIds) {

        public Cluster {
            samples = samples == null ? List.of() : List.copyOf(samples);
            caseIds = caseIds == null ? List.of() : List.copyOf(caseIds);
        }
    }

    /** 样例用例；caseId 可能为 null（手工导入的失败行没有关联台账）。 */
    public record Sample(Long caseId, String caseCode, String caseTitle) {
    }
}
