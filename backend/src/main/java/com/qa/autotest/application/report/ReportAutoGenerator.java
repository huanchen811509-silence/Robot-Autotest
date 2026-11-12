package com.qa.autotest.application.report;

import com.qa.autotest.application.execution.RunCompletionListener;
import com.qa.autotest.application.execution.RunMetadataService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.application.repository.TestReportRepository;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.report.TestReport;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 报告自动生成（FR-v5.4-01 / D1–D3 / W54-01）。
 *
 * <p>默认<b>关闭</b>。理由不是保守，而是「报告」在多数团队里是对外交付物：
 * 自动生成意味着任何一次试跑都可能产出看起来正式的文档，而它其实只是调试。
 * 因此必须由人显式打开，且只对 {@code mode=real} 的执行生效。
 *
 * <p><b>四条件全满足才生成</b>（R2）：run 终态 + {@code mode=real} + 存在 {@code test_result}
 * + 项目已绑模板。少一条都不生成，并且把「缺哪条」写进返回的 {@code Decision}——
 * 用户打开开关后发现没生成，得能立刻知道是哪一条没满足。
 *
 * <p><b>失败不影响 run 结论</b>（R3）：挂在 {@link RunCompletionListener} 上，事务提交后执行。
 * 报告生成失败最多是「少一份报告」，而 run 的通过/失败是已经发生的事实，不能因为
 * 一个渲染异常就被改写。
 *
 * <p><b>幂等键的偏差记录</b>：PRD R4 写的是 {@code (run_id, template_id)}，但
 * {@code test_report} 表没有 {@code template_id} 列（报告模板与报告之间从来没有关联字段）。
 * 这里退化为「同一 run 已有一份自动生成的报告就不再建」，并把模板 id 记在
 * {@code source_summary} 里便于追溯。要精确到模板级幂等需要给 {@code test_report}
 * 加列，那属于表结构变更，不在本包范围内——先按能验的方式做，把差距记下来，
 * 好过为了形式上的精确去改一张核心表。
 */
@Service
public class ReportAutoGenerator implements RunCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(ReportAutoGenerator.class);

    /** 配置键：与 {@code project_setting} 的 key 一致。 */
    public static final String KEY_ENABLED = "report.auto_generate";
    public static final String KEY_TRIGGER = "report.trigger";

    public static final String TRIGGER_RUN_FINISHED = "run_finished";
    public static final String TRIGGER_SCHEDULED = "scheduled";
    public static final String TRIGGER_MANUAL = "manual";

    /** 自动生成的报告标题前缀，用于列表页与人工报告区分。 */
    static final String TITLE_PREFIX = "自动报告 ";

    private final ProjectSettingRepository settings;
    private final RunMetadataService metadata;
    private final ReportTemplateService templates;
    private final TestReportService reports;
    private final TestReportRepository reportRepository;
    private final AuditService audit;
    private final boolean enabledByDefault;

    @Autowired
    public ReportAutoGenerator(ProjectSettingRepository settings, RunMetadataService metadata,
                               ReportTemplateService templates, TestReportService reports,
                               TestReportRepository reportRepository, AuditService audit) {
        this(settings, metadata, templates, reports, reportRepository, audit, false);
    }

    /** 测试用：可把「全局默认开关」设成 true，免得每个用例都去造一份配置。 */
    ReportAutoGenerator(ProjectSettingRepository settings, RunMetadataService metadata,
                        ReportTemplateService templates, TestReportService reports,
                        TestReportRepository reportRepository, AuditService audit,
                        boolean enabledByDefault) {
        this.settings = settings;
        this.metadata = metadata;
        this.templates = templates;
        this.reports = reports;
        this.reportRepository = reportRepository;
        this.audit = audit;
        this.enabledByDefault = enabledByDefault;
    }

    /**
     * 判定结果。
     *
     * @param reason 不生成的原因（生成时为 {@code null}）；页面直接展示这句话，
     *               不要写「不满足条件」这种看不出是哪个条件的措辞
     */
    public record Decision(boolean generate, String reason, Long templateId) {
    }

    /**
     * 四条件判定（纯逻辑，便于单测）。
     *
     * @param enabled         项目开关
     * @param trigger         触发器；只有 {@code run_finished} 会在终态时触发本方法
     * @param terminal        run 是否终态
     * @param mode            执行模式（{@code fake} / {@code real} / null 视为未记录）
     * @param testResultCount 本次 run 落库的用例结果条数
     * @param templateId      项目已绑模板 id；null 表示没绑
     * @param alreadyGenerated 同一 run 是否已有自动报告
     */
    public static Decision decide(boolean enabled, String trigger, boolean terminal, String mode,
                                  int testResultCount, Long templateId, boolean alreadyGenerated) {
        if (!enabled) {
            return new Decision(false, "项目未开启自动生成报告（" + KEY_ENABLED + "=false）", null);
        }
        String normalized = trigger == null || trigger.isBlank()
                ? TRIGGER_RUN_FINISHED : trigger.trim().toLowerCase(Locale.ROOT);
        if (!TRIGGER_RUN_FINISHED.equals(normalized)) {
            return new Decision(false, "触发器为 " + normalized + "，不由 run 终态触发", null);
        }
        if (!terminal) {
            return new Decision(false, "run 尚未进入终态", null);
        }
        if (mode == null || mode.isBlank()) {
            return new Decision(false, "run 未记录执行模式（run_metadata.mode 缺失），"
                    + "无法确认是正式执行", null);
        }
        if (!"real".equalsIgnoreCase(mode.trim())) {
            return new Decision(false, "执行模式为 " + mode.trim() + "，只对 real 自动出报告"
                    + "（fake/预检可手工出预检报告）", null);
        }
        if (testResultCount <= 0) {
            return new Decision(false, "本次 run 没有落库的用例结果，报告会是一份空壳", null);
        }
        if (templateId == null) {
            return new Decision(false, "项目未绑定报告模板，先绑定再开启自动生成", null);
        }
        if (alreadyGenerated) {
            return new Decision(false, "该 run 已有自动生成的报告（幂等：不重复建）",
                    templateId);
        }
        return new Decision(true, null, templateId);
    }

    /** 判定 + 生成；返回生成出来的报告（未生成时为空）。 */
    public Optional<TestReport> generate(RunRecord run) {
        if (run == null || run.runId() == null) {
            return Optional.empty();
        }
        Decision decision = decide(run);
        if (!decision.generate()) {
            log.debug("不自动生成报告 run={}：{}", run.runId(), decision.reason());
            return Optional.empty();
        }
        TestReport report = reports.create(run.projectId(), run.runId(),
                TITLE_PREFIX + run.runId());
        audit.record("report:auto-generate", "test_report", String.valueOf(report.id()),
                "run=" + run.runId() + ", template=" + decision.templateId());
        return Optional.of(report);
    }

    /** 取数后调用 {@link #decide(boolean, String, boolean, String, int, Long, boolean)}。 */
    public Decision decide(RunRecord run) {
        if (run == null || run.runId() == null) {
            return new Decision(false, "run 为空", null);
        }
        Map<String, String> all = settings.findAll(run.projectId(), "report.");
        boolean enabled = parseBoolean(all.get("auto_generate"), enabledByDefault);
        String trigger = all.get("trigger");

        String mode;
        try {
            mode = metadata.list(run.projectId(), run.runId()).get("mode");
        } catch (RuntimeException e) {
            // 元数据取不到就当「未记录」，不因此放行自动生成
            mode = null;
        }

        Long templateId = null;
        try {
            List<ReportTemplateView> list = templates.list(run.projectId());
            if (!list.isEmpty()) {
                templateId = list.get(0).id();
            }
        } catch (RuntimeException e) {
            templateId = null;
        }

        boolean already;
        try {
            already = reportRepository.listByProject(run.projectId()).stream()
                    .anyMatch(item -> run.runId().equals(item.runId())
                            && item.title() != null && item.title().startsWith(TITLE_PREFIX));
        } catch (RuntimeException e) {
            // 查不到「是否已生成」时按「已生成」处理：宁可少生成一份，也不重复堆报告
            already = true;
        }

        return decide(enabled, trigger, run.finishedAt() != null, mode,
                run.totalTests(), templateId, already);
    }

    @Override
    public void onCompleted(RunRecord run) {
        try {
            generate(run);
        } catch (RuntimeException e) {
            // R3：失败不影响 run 结论，只留痕
            log.warn("自动生成报告失败 run={}：{}",
                    run == null ? "(null)" : run.runId(), e.toString());
        }
    }

    /** 布尔解析：只认 true/1/yes/on，其余（含空、错写）按 false。 */
    static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> fallback;
        };
    }
}
