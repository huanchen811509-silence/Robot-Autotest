package com.qa.autotest.application.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.ProjectRepository;
import com.qa.autotest.common.security.SecretRedactor;
import com.qa.autotest.domain.report.MetadataGate;
import com.qa.autotest.domain.report.ReportSnapshot;
import com.qa.autotest.domain.report.ReportTemplate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 双视图渲染（RPTX-03 / RPTX-04 / C5 字段白名单 / TPLX-02 按模板裁剪）。
 *
 * <p>EXTERNAL 采用<b>白名单</b>而非黑名单：未来新增字段默认不出去。
 * 所有字符串再过一次 scrub，确保 IP / 命令 / 凭据 / 绝对路径不会随 metadata 泄漏。
 *
 * <p>白名单本身住在 {@link ReportFieldWhitelist}（TPLX-01 抽出）：报告模板要给用户
 * 「勾选哪些字段」，那份清单必须能被接口和界面引用，不能只埋在本类里。
 * 本类只负责**按白名单渲染**，不定义白名单。
 *
 * <p><b>模板只从快照里读</b>（TPLX-02）：冻结时把模板（id / 版本 / 字段清单 / 自定义标题）
 * 一并写进 {@code snapshot_json}，渲染时不再回查 {@code report_template} 表。
 * 若渲染时去查「当前模板」，改一次模板就会把所有历史已冻结报告的字段集合改掉——
 * 那等于冻结没有发生。这是本类最重要的一条纪律。
 */
@Service
public class ReportViewRenderer {

    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)(password|passwd|api[_-]?key|secret|token|authorization)\\s*[:=]\\s*\\S+");
    private static final Pattern ABS_PATH = Pattern.compile("(?:/home/|/root/|/mnt/|/var/)\\S+");
    private static final Pattern COMMAND = Pattern.compile(
            "(?i)(robot|pabot|pytest|iperf3|ssh|mysql)\\s+-{1,2}[A-Za-z]\\S*");

    private final ObjectMapper mapper;
    private final ProjectRepository projects;

    /**
     * v5.22.0 / W72-02：注入 {@link ProjectRepository} 只为把 `projectName` 渲染成真名。
     *
     * <p>原实现是 `"project-" + projectId` 拼出来的，实测对外视图显示 `project-95` 这种
     * 内部编号——对外文件里出现数据库自增 ID 既无意义也不该露。按 R6 走构造器注入。
     */
    public ReportViewRenderer(ObjectMapper mapper, ProjectRepository projects) {
        this.mapper = mapper;
        this.projects = projects;
    }

    /**
     * 快照里记的模板回指（TPLX-02）：给页面显示「这份报告是按哪个模板第几版冻结的」。
     *
     * <p>v5.14.0 / W64-11 追加两项**查询期元信息**：{@code kernel} 是模板内核，
     * {@code templateSource} 是四级回退落在哪一级，供界面判断「当前使用通用模板」。
     * 它们不是报告字段，不进 C5 白名单、不进快照 JSON、不影响 snapshot_hash；
     * 历史快照（没有模板回指）两项为 null，界面不提示，行为与迁移前一致。
     */
    public record TemplateRef(Long id, Integer version, String name, List<String> fieldKeys,
                              String kernel, String templateSource) {

        /** 兼容 TPLX-02 的 4 参写法：历史 / 未绑定模板一律不带元信息。 */
        public TemplateRef(Long id, Integer version, String name, List<String> fieldKeys) {
            this(id, version, name, fieldKeys, null, null);
        }

        public boolean bound() {
            return id != null;
        }
    }

    /** 对内执行报告：关键字树 / 命令 / 原始输出的入口，保留完整字段。 */
    public Map<String, Object> internal(ReportSnapshot snapshot) {
        Map<String, Object> view = read(snapshot.json());
        view.put("snapshotHash", snapshot.hash());
        view.put("sourceSummary", scrub(snapshot.sourceSummary()));
        view.put("renderMode", "INTERNAL");
        view.put("keywordTree", Map.of("root", view.getOrDefault("runId", "")));
        view.put("commands", java.util.List.of());
        view.put("rawOutput", "");
        return view;
    }

    /** 对外测试报告：只放白名单字段，**按快照内模板的顺序与取舍**，且逐值脱敏。 */
    public Map<String, Object> external(ReportSnapshot snapshot, long reportId, String title,
                                        String frozenAt, String watermark) {
        Map<String, Object> all = read(snapshot.json());
        Map<String, Object> view = buildView(snapshot, all, reportId, title, frozenAt, watermark);
        // 白名单自检：视图里出现非白名单键，说明有人加了字段却没登记白名单，直接炸出来
        for (String key : view.keySet()) {
            if (!ReportFieldWhitelist.EXTERNAL_FIELDS.contains(key)) {
                throw new IllegalStateException("EXTERNAL 视图出现非白名单字段: " + key);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : order(all)) {
            if (!view.containsKey(key)) {
                // 模板里选了、但视图里没有（字段本身无值）：跳过而不是写空，
                // 避免「模板有 21 行、导出却 21 行全是空」这种看不出问题的坏结果。
                continue;
            }
            Object value = view.get(key);
            result.put(key, value instanceof String s ? scrub(s) : value);
        }
        return result;
    }

    /** 对外视图的字段标题（字段 key → 模板里配的自定义标题），供导出用。 */
    public Map<String, String> fieldTitles(ReportSnapshot snapshot) {
        Map<String, String> titles = new LinkedHashMap<>();
        for (ReportTemplate.Field field : templateFields(read(snapshot.json()))) {
            if (field.titleOverride() != null && !field.titleOverride().isBlank()) {
                titles.put(field.fieldKey(), field.titleOverride());
            }
        }
        return titles;
    }

    /**
     * 读快照里的布尔标记（{@code exportable} 等）。
     *
     * <p>为什么从快照读而不是回查 run：报告的状态可能比 run 活得久（run 会被保留策略清理、
     * 也可能被软删）。导出闸如果回查 run，就会出现「报告还在、闸查不到源」的 404——
     * 那正是 v5.22 调研发现的 L-22-01（同一份报告两个入口给出相反答案）。
     */
    public boolean flag(ReportSnapshot snapshot, String key, boolean fallback) {
        Object raw = read(snapshot.json()).get(key);
        if (raw instanceof Boolean value) {
            return value;
        }
        return fallback;
    }

    /** 读快照里的字符串数组（{@code metadataMissing}）；缺失或类型不符返回空列表。 */
    public List<String> strings(ReportSnapshot snapshot, String key) {
        if (!(read(snapshot.json()).get(key) instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(item -> item != null).map(String::valueOf).toList();
    }

    /**
     * 快照里的用例明细（v5.22.0 / W72-03 写入的 {@code cases} 数组）。
     *
     * @return {@code null} = 该键不存在（历史报告 / 冻结时未采集）；空列表 = 采集到 0 条。
     *         导出层必须能区分这两种，否则「没记录」会被写成「确实没有用例」。
     */
    public List<Map<String, Object>> caseRows(ReportSnapshot snapshot) {
        Object raw = read(snapshot.json()).get("cases");
        if (!(raw instanceof List<?> list)) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    row.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                out.add(row);
            }
        }
        return out;
    }

    /** 快照里记录的模板回指；没有模板（历史快照）时返回 `bound=false` 的空值。 */
    public TemplateRef templateRef(ReportSnapshot snapshot) {
        Map<String, Object> all = read(snapshot.json());
        Object raw = all.get("template");
        List<String> keys = templateFields(all).stream()
                .map(ReportTemplate.Field::fieldKey).toList();
        if (!(raw instanceof Map<?, ?> tpl)) {
            return new TemplateRef(null, null, null, keys);
        }
        Long id = tpl.get("id") instanceof Number n ? n.longValue() : null;
        Integer version = tpl.get("version") instanceof Number n ? n.intValue() : null;
        Object name = tpl.get("name");
        return new TemplateRef(id, version, name == null ? null : String.valueOf(name), keys);
    }

    private Map<String, Object> buildView(ReportSnapshot snapshot, Map<String, Object> all,
                                          long reportId, String title, String frozenAt,
                                          String watermark) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reportId", reportId);
        view.put("title", scrub(title));
        view.put("version", snapshot.version());
        view.put("frozenAt", frozenAt);
        view.put("conclusion", conclusion(all));
        for (String key : new String[]{"passCount", "failCount", "skipCount",
                "passRate", "caseTotal", "startedAt", "finishedAt"}) {
            if (all.containsKey(key)) {
                view.put(key, all.get(key));
            }
        }
        // v5.22.0：`caseCovered` / `coverageRate` / `failureDistribution` / `trend` 已移除。
        // 它们没有任何数据源（分别是「恒等 caseTotal」「恒 100.0」「恒等 failCount」「恒空数组」），
        // 留在对外文件里就是失实陈述。C5 文档 v1.2 已同步删除。
        putMetadata(view, all);
        view.put("disclaimer", "INTERNAL USE ONLY - NOT FOR EXTERNAL DISTRIBUTION");
        view.put("watermark", watermark);
        return view;
    }

    /**
     * 字段顺序：模板有就用模板顺序，模板没有（历史快照 / 未绑定模板）就用白名单顺序。
     *
     * <p>必填字段做兜底追加：模板是旧版本、或白名单后来收紧，都不能让「免责声明 / 水印」
     * 从对外报告里消失——那是合规字段，不是可选装饰。追加时按白名单顺序，
     * 保证同一份快照每次渲染顺序都一样。
     */
    private List<String> order(Map<String, Object> all) {
        List<ReportTemplate.Field> fields = templateFields(all);
        if (fields.isEmpty()) {
            return ReportFieldWhitelist.EXTERNAL_ORDER;
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        fields.forEach(field -> keys.add(field.fieldKey()));
        for (String key : ReportFieldWhitelist.EXTERNAL_ORDER) {
            if (ReportFieldWhitelist.isRequired(key)) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }

    /**
     * 从快照 JSON 的 {@code template.fields} 解析字段清单。
     *
     * <p>白名单在这里**再过滤一次**：模板是白名单的子集，但白名单可能在两次发布之间收紧，
     * 旧快照里的模板字段可能已经不被允许——渲染层是最后一道闸，不能因为「模板里有」就放行。
     */
    private static List<ReportTemplate.Field> templateFields(Map<String, Object> all) {
        Object raw = all.get("template");
        if (!(raw instanceof Map<?, ?> template)
                || !(template.get("fields") instanceof List<?> list)) {
            return List.of();
        }
        List<ReportTemplate.Field> fields = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> field)) {
                continue;
            }
            Object rawKey = field.get("fieldKey");
            String key = rawKey == null ? "" : String.valueOf(rawKey).trim();
            if (key.isEmpty() || !ReportFieldWhitelist.allows(key)) {
                continue;
            }
            int sortOrder = field.get("sortOrder") instanceof Number n ? n.intValue() : fields.size();
            Object rawTitle = field.get("titleOverride");
            String override = rawTitle == null ? null : String.valueOf(rawTitle);
            fields.add(new ReportTemplate.Field(key, sortOrder,
                    override == null || override.isBlank() ? null : override));
        }
        fields.sort(Comparator.comparingInt(ReportTemplate.Field::sortOrder));
        return fields;
    }

    /**
     * v5.22.0 / W72-02：metadata 七键 + 真项目名。
     *
     * <p>旧实现只放 `swBuild` / `caseKind`，而快照里 `QA_PROJECT` / `DUT` / `LINK` 一直有值——
     * 「数据在快照里、却没渲染出来」是对外报告最隐蔽的一种缺字段：单看导出件完全看不出少了东西，
     * 只有把快照拆开对才知道。
     *
     * <p>`runId` / `caseId` 优先取 metadata（output.xml 里带的），缺失时回退到快照根键：
     * 老快照没有这两个 metadata 键，但根键 `runId` 一直存在，不能因为没登记 metadata 就空着。
     */
    private void putMetadata(Map<String, Object> view, Map<String, Object> all) {
        if (all.get("metadata") instanceof Map<?, ?> map) {
            view.put("swBuild", value(map, "SW_BUILD"));
            view.put("caseKind", value(map, "CASE_KIND"));
            view.put("qaProject", value(map, "QA_PROJECT"));
            view.put("dut", value(map, "DUT"));
            view.put("link", value(map, "LINK"));
            view.put("caseId", firstNonBlank(value(map, "CASE_ID"), plain(all.get("caseId"))));
            view.put("runId", firstNonBlank(value(map, "RUN_ID"), plain(all.get("runId"))));
        } else {
            for (String key : new String[]{"swBuild", "caseKind", "qaProject", "dut",
                    "link", "caseId", "runId"}) {
                view.put(key, "");
            }
            view.put("caseId", plain(all.get("caseId")));
            view.put("runId", plain(all.get("runId")));
        }
        view.put("projectName", projectName(all));
    }

    /**
     * 项目名取真名，取不到才回退成内部编号。
     *
     * <p>回退值不是"更好的假名"，而是**让缺失可见**：`project-95` 一眼能看出没查到，
     * 若回退成空串，对外文件里就是一片空白，看不出是数据问题还是渲染问题。
     */
    private String projectName(Map<String, Object> all) {
        Object raw = all.get("projectId");
        if (!(raw instanceof Number n)) {
            return "";
        }
        long id = n.longValue();
        return projects.findById(id)
                .map(project -> scrub(project.name() == null ? "" : project.name()))
                .filter(name -> !name.isBlank())
                .orElse("project-" + id);
    }

    /** 快照根键取值：统一转字符串并过脱敏，null 转空串。 */
    private static String plain(Object raw) {
        return raw == null ? "" : scrub(String.valueOf(raw));
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    /** Map&lt;?,?&gt; 的 getOrDefault 会把默认值也推成 capture，故用 get + 显式 Object。 */
    private static String value(Map<?, ?> map, String key) {
        Object found = map.get(key);
        return found == null ? "" : scrub(String.valueOf(found));
    }

    /**
     * 结论口径（v5.7.0 / FR-v5.7-13，复核 W57-50）：<b>先看判据汇总</b>，只有判据全 PASS 才 PASS。
     *
     * <p>旧的「{@code failCount == 0} 就 PASS」把「进程没报错」当成「达标」，正是白泽调研指出的
     * 核心缺陷。现在：
     * <ul>
     *   <li>判据汇总为 PASS / FAIL → 直接采用；</li>
     *   <li>汇总缺失或 UNDETERMINED → 判 {@code UNDETERMINED}，**不再回落成 PASS**；</li>
     *   <li>仅有历史快照（没有 criteriaConclusion 键）时才退回计数口径，并显式标注
     *       {@code PASS(count-only)}，让读者知道这条结论没有判据支撑。</li>
     * </ul>
     */
    private static String conclusion(Map<String, Object> all) {
        Object declared = all.get("criteriaConclusion");
        if (declared != null) {
            String value = String.valueOf(declared);
            if (!value.isBlank() && !"UNDETERMINED".equals(value)) {
                return value;
            }
            return "UNDETERMINED";
        }
        int fail = ((Number) all.getOrDefault("failCount", 0)).intValue();
        return fail == 0 ? "PASS(count-only)" : "FAIL";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        try {
            return new LinkedHashMap<>(mapper.readValue(json, Map.class));
        } catch (Exception e) {
            throw new IllegalStateException("报告快照不可解析", e);
        }
    }

    /** metadata 只保留 C5 白名单七键，且值一律脱敏。 */
    public static Map<String, String> safeMetadata(Map<String, String> metadata) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (metadata == null) {
            return safe;
        }
        for (String key : ReportFieldWhitelist.METADATA_ORDER) {
            String value = metadata.get(key);
            if (value != null) {
                safe.put(key, scrub(value));
            }
        }
        return safe;
    }

    /**
     * v5.14.0 / W7 真实 HTTP 探针修复：三段式附录的 metadata 十项口径。
     *
     * <p>{@link #safeMetadata(Map)} 是「对外视图」的 C5 七键白名单，不能拿去填
     * {@code EhtSections.metadataRequired} 的十项清单 —— 否则另外五项永远显示「未提供」，
     * 即使 run 的 output.xml 真的带了这些值。这里按 {@link MetadataGate#REQUIRED}
     * 逐项取真值并同样过 {@link #scrub}：只放宽键集合，不放宽脱敏；对外五键口径零改动。
     */
    public static Map<String, String> safeSectionMetadata(Map<String, String> metadata) {
        Map<String, String> safe = new LinkedHashMap<>();
        if (metadata == null) {
            return safe;
        }
        for (String key : MetadataGate.REQUIRED) {
            String value = metadata.get(key);
            if (value != null && !value.isBlank()) {
                safe.put(key, scrub(value));
            }
        }
        return safe;
    }

    public static String scrub(String value) {
        if (value == null) {
            return "";
        }
        // 先走统一的凭据脱敏（FR-v4.0-01），再走报告侧的 IP / 路径 / 命令遮蔽。
        // 顺序不能反：COMMAND 规则只吃掉命令头，会把 -u user:pass 里的口令留在原地。
        String s = SecretRedactor.redact(value);
        s = IP.matcher(s).replaceAll("[REDACTED]");
        s = CREDENTIAL.matcher(s).replaceAll("[REDACTED]");
        s = ABS_PATH.matcher(s).replaceAll("[REDACTED]");
        return COMMAND.matcher(s).replaceAll("[REDACTED]");
    }

    public static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
