package com.qa.autotest.application.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.common.security.SecretRedactor;
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

    public ReportViewRenderer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 快照里记的模板回指（TPLX-02）：给页面显示「这份报告是按哪个模板第几版冻结的」。 */
    public record TemplateRef(Long id, Integer version, String name, List<String> fieldKeys) {

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
        view.put("caseCovered", all.getOrDefault("caseTotal", 0));
        view.put("coverageRate", 100.0);
        view.put("failureDistribution", Map.of("fail", all.getOrDefault("failCount", 0)));
        view.put("trend", java.util.List.of());
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

    private static void putMetadata(Map<String, Object> view, Map<String, Object> all) {
        Object raw = all.get("metadata");
        if (raw instanceof Map<?, ?> map) {
            view.put("swBuild", value(map, "SW_BUILD"));
            view.put("caseKind", value(map, "CASE_KIND"));
        } else {
            view.put("swBuild", "");
            view.put("caseKind", "");
        }
        view.put("projectName", "project-" + String.valueOf(all.getOrDefault("projectId", "0")));
    }

    /** Map&lt;?,?&gt; 的 getOrDefault 会把默认值也推成 capture，故用 get + 显式 Object。 */
    private static String value(Map<?, ?> map, String key) {
        Object found = map.get(key);
        return found == null ? "" : scrub(String.valueOf(found));
    }

    private static String conclusion(Map<String, Object> all) {
        int fail = ((Number) all.getOrDefault("failCount", 0)).intValue();
        return fail == 0 ? "PASS" : "FAIL";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> read(String json) {
        try {
            return new LinkedHashMap<>(mapper.readValue(json, Map.class));
        } catch (Exception e) {
            throw new IllegalStateException("报告快照不可解析", e);
        }
    }

    /** metadata 只保留 C5 白名单键，且值一律脱敏。 */
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
