package com.qa.autotest.application.report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code case_key} 的**唯一**切分规则（v5.14.0 / FR-v5.14-02 / W64-02）。
 *
 * <p>在此之前，规则是 {@link ReportTreeService#loadTree} 里的一段内联代码：连接键格式
 * {@code suitePath.caseName}，分隔点取「最后一个 / 之后的第一个 .」，这样用例名里带点
 * （pytest 参数化 {@code test_x[1.0]}）也不会把套件路径切错；套件路径再按 {@code /} 分段。
 *
 * <p>v5.14.0 要让列表页显示「套件数」，最危险的做法是照草案再写一套「RF 按 /、
 * pytest 按 .」的算法——那必然与详情树打架（列表写 12、树里 11 个）。所以规则只保留
 * 这一份：树构建调 {@link #split(String)} / {@link #segments(String)}，入库落列调
 * {@link #topLevelSuiteCount(Collection)}，二者共用同一实现。
 *
 * <p>注意：现有链路**不读 run.kernel**，也不按内核换分隔符；pytest 的 case_key 可能
 * 是 nodeid 或用例库编号（没有路径分隔符），此时树的一级子节点数就是 0，套件数也
 * 必须是 0。宁可与树一致，也不要「看起来更合理」的猜测值。
 */
public final class CaseKeyPath {

    private CaseKeyPath() {
    }

    /** 切分结果：套件路径 + 用例名。二者都可能为空串，但不会是 null。 */
    public record Split(String suitePath, String caseName) {
    }

    public static Split split(String caseKey) {
        String key = caseKey == null ? "" : caseKey;
        int slash = key.lastIndexOf('/');
        int dot = key.indexOf('.', slash + 1);
        String suitePath = dot >= 0 ? key.substring(0, dot) : key;
        String caseName = dot >= 0 ? key.substring(dot + 1) : key;
        return new Split(suitePath, caseName);
    }

    /** 套件路径的非空分段；与树的 {@code SuiteBuilder.child()} 顺序一致。 */
    public static List<String> segments(String suitePath) {
        if (suitePath == null || suitePath.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : suitePath.split("/")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 套件树的一级子节点数（= 第一段去重后的数量）。
     *
     * @return {@code null} 表示**不可靠**：没有用例，或出现空 key。调用方应写 NULL 并
     *         在界面显示「—」，不得当成 0 —— 0 是「确认没有子套件」的事实，未知不是事实。
     */
    public static Integer topLevelSuiteCount(Collection<String> caseKeys) {
        if (caseKeys == null || caseKeys.isEmpty()) {
            return null;
        }
        Set<String> first = new LinkedHashSet<>();
        for (String key : caseKeys) {
            if (key == null || key.isBlank()) {
                return null;
            }
            List<String> parts = segments(split(key).suitePath());
            if (!parts.isEmpty()) {
                first.add(parts.get(0));
            }
        }
        return first.size();
    }
}
