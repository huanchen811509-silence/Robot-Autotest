package com.qa.autotest.application.report;

import com.qa.autotest.application.repository.EhtSectionRepository;
import com.qa.autotest.application.repository.ReportTemplateRepository;
import com.qa.autotest.application.repository.TestReportRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.report.ReportRecord;
import com.qa.autotest.domain.report.ReportSnapshot;
import com.qa.autotest.domain.report.ReportSourceType;
import com.qa.autotest.domain.report.ReportState;
import com.qa.autotest.domain.report.ReportTemplate;
import com.qa.autotest.domain.report.TestReport;
import com.qa.autotest.domain.report.TestReportExport;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 测试报告服务（RPTX-01 状态机 / RPTX-02 冻结 / RPTX-05 导出与保留 / TPLX-02 按模板冻结）。 */
@Service
public class TestReportService {

    private final TestReportRepository repository;
    private final ReportSnapshotService snapshots;
    private final ReportViewRenderer renderer;
    private final ReportExportService exports;
    private final ReportQueryService queries;
    private final ReportTemplateRepository templates;
    private final TestSetRepository caseSets;
    private final EhtSectionRepository ehtSections;
    private final ReportPdfService pdfs;

    public TestReportService(TestReportRepository repository, ReportSnapshotService snapshots,
                             ReportViewRenderer renderer, ReportExportService exports,
                             ReportQueryService queries, ReportTemplateRepository templates,
                             TestSetRepository caseSets, EhtSectionRepository ehtSections,
                             ReportPdfService pdfs) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.renderer = renderer;
        this.exports = exports;
        this.queries = queries;
        this.templates = templates;
        this.caseSets = caseSets;
        this.ehtSections = ehtSections;
        this.pdfs = pdfs;
    }

    public TestReport find(long id) {
        return repository.find(id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "测试报告不存在: " + id));
    }

    public List<TestReport> list(long projectId) {
        return repository.listByProject(projectId);
    }

    public TestReport create(long projectId, String runId, String title) {
        if (runId == null || runId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "runId 不能为空");
        }
        return createInternal(projectId, runId, ReportSourceType.RUN, null, title);
    }

    /**
     * 按用例集建草稿（FR-v3.10-03）。
     *
     * <p>「按用例集」与「按执行记录」在执行层没有区别：这里先把意图解析成**唯一一条 run**
     * （该集最近一次，或用户指定的那一次），后续建快照 / 冻结 / 渲染完全复用旧链路。
     * 这样做的直接好处是——判据「旧路径零回归」不需要另写回归套件，两条路径共用同一段代码。
     *
     * <p>三种失败都要给出可执行提示，而不是 500：
     * ① 用例集不存在 → 404（可能已被删）；
     * ② 用例集从未执行过 → 409「先跑一次」（建报告要有数据可写，不能建空报告）；
     * ③ 指定的 run 不属于该集 → 400（否则报告标题写着 A 集、内容来自 B 集）。
     */
    public TestReport createFromCaseSet(long projectId, long caseSetId, String preferredRunId,
                                        String title) {
        caseSets.find(projectId, caseSetId).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "用例集不存在: " + caseSetId));
        String runId = resolveRun(projectId, caseSetId, preferredRunId);
        return createInternal(projectId, runId, ReportSourceType.CASE_SET, caseSetId, title);
    }

    private String resolveRun(long projectId, long caseSetId, String preferredRunId) {
        if (preferredRunId != null && !preferredRunId.isBlank()) {
            if (!caseSets.containsRun(projectId, caseSetId, preferredRunId)) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "执行记录 " + preferredRunId + " 不属于该用例集");
            }
            return preferredRunId;
        }
        return caseSets.latestRunId(projectId, caseSetId).orElseThrow(
                () -> new BusinessException(ErrorCode.CONFLICT,
                        "该用例集还没有执行记录，先跑一次再建报告"));
    }

    private TestReport createInternal(long projectId, String runId, ReportSourceType sourceType,
                                      Long caseSetId, String title) {
        int version = repository.maxVersion(projectId, runId) + 1;
        ReportRecord detail = queries.detail(projectId, runId);
        ReportSnapshot snapshot = snapshots.build(detail, version,
                defaultTemplate(projectId, runId), sectionsOf(detail),
                queries.cases(projectId, runId));
        long id = repository.insert(projectId, runId, sourceType, caseSetId,
                title == null || title.isBlank() ? "报告 " + runId : title, version, snapshot);
        repository.audit(id, "CREATE", null, ReportState.DRAFT.name(), null);
        return find(id);
    }

    /**
     * 冻结：**这一刻**的模板被写进快照，之后改模板不再影响它。
     *
     * <p>创建（DRAFT）时也会按当次默认模板物化一份，但那份只是草稿预览；
     * 口径以冻结这一份为准——只有冻结件才是对外交付物。
     *
     * <p>版本号按 **run 全局**分配（{@code maxVersion + 1}），而不是「本报告版本 + 1」：
     * 唯一键是 {@code uk_report_run_version (project_id, run_id, version)}，同一个 run 上
     * 可以有多份报告（按执行记录建的、按用例集建的）。用「本报告 +1」时，先冻结第 1 份
     * 会占掉第 2 份刚用过的版本号，直接撞唯一键 500（2026-11-09 契约验证实测到）。
     */
    public TestReport freeze(long id) {
        TestReport report = find(id);
        require(report.state().canTransitionTo(ReportState.FROZEN), "只有 DRAFT 可以冻结");
        int version = repository.maxVersion(report.projectId(), report.runId()) + 1;
        ReportRecord detail = queries.detail(report.projectId(), report.runId());
        ReportSnapshot snapshot = snapshots.build(detail, version,
                defaultTemplate(report.projectId(), report.runId()), sectionsOf(detail),
                queries.cases(report.projectId(), report.runId()));
        repository.freeze(id, snapshot);
        repository.audit(id, "FREEZE", report.state().name(), ReportState.FROZEN.name(), null);
        return find(id);
    }

    public TestReport transition(long id, ReportState target) {
        TestReport report = find(id);
        require(report.state().canTransitionTo(target),
                "不允许的状态流转: " + report.state() + " -> " + target);
        repository.changeState(id, target);
        repository.audit(id, "TRANSITION", report.state().name(), target.name(), null);
        return find(id);
    }

    /** 渲染只读快照表，绝不回查 run、也绝不回查当前模板 —— 这是「冻结后自包含」的实现保证。 */
    public Map<String, Object> render(long id, boolean external) {
        TestReport report = find(id);
        ReportSnapshot snapshot = snapshotOf(report);
        if (!external) {
            return renderer.internal(snapshot);
        }
        return renderer.external(snapshot, report.id(), report.title(),
                report.frozenAt() == null ? "" : report.frozenAt().toString(),
                ReportExportService.WATERMARK);
    }

    /**
     * 报告是按哪个模板第几版冻结的；历史快照没有模板回指时 {@code bound=false}。
     *
     * <p>v5.14.0 / W64-11：字段清单仍**只从快照读**（TPLX-02 冻结纪律），
     * 但补两项查询期元信息：模板 kernel 与四级回退结果。平台模板只读、项目模板的
     * kernel 创建后不可改（B-15），所以现场推断不会让历史报告漂移；模板若已被删除，
     * 原样返回快照回指，不编造来源。
     */
    /**
     * v5.14.0 / W64-14：三段式数据装配。metadata 取冻结用的同一份 ReportRecord，
     * 与快照 JSON 同源；装配失败不阻断报告创建（仓储本身返回可解释空值）。
     */
    private EhtSections sectionsOf(ReportRecord detail) {
        return ehtSections.load(detail.projectId(), detail.runId())
                .withMetadata(ReportViewRenderer.safeSectionMetadata(detail.metadata()));
    }

    /** 详情页「证据层」卡片的只读数据源：现场装配，字段清单仍以冻结快照为准。 */
    public EhtSections sections(long id) {
        TestReport report = find(id);
        try {
            return sectionsOf(queries.detail(report.projectId(), report.runId()));
        } catch (RuntimeException ex) {
            return ehtSections.load(report.projectId(), report.runId());
        }
    }

    public ReportViewRenderer.TemplateRef template(long id) {
        TestReport report = find(id);
        ReportViewRenderer.TemplateRef ref = renderer.templateRef(snapshotOf(report));
        if (!ref.bound()) {
            return ref;
        }
        return templates.find(report.projectId(), ref.id())
                .map(t -> new ReportViewRenderer.TemplateRef(
                        ref.id(), ref.version(), ref.name(), ref.fieldKeys(), t.kernel(),
                        TemplateSelection.source(TemplateSelection.rank(
                                t.projectId(), t.kernel(), report.projectId(),
                                templates.runKernel(report.projectId(), report.runId())))))
                .orElse(ref);
    }

    /**
     * 只读 HTML 快照视图（v5.14.0 / W64-05 / FR-v5.14-04）。
     *
     * <p>原有 {@code GET /api/test-reports/{id}} 只返回字段 Map，前端拿不到
     * {@code snapshot_html}，所以「能建不能看」。这里**不复用 render**（render 是字段视图、
     * 受白名单自检保护），而是单独开一条只读通道：直接读冻结时落库的 HTML，
     * 经 {@link ReportHtmlSanitizer} 清洗后交给 {@code iframe sandbox}。
     *
     * <p>刻意不从当前模板重新渲染：冻结件必须与当时打印的一致。无快照（历史脏数据）
     * 返回 {@code hasSnapshot=false} 而不是抛异常，让详情页显示可解释的空态。
     */
    public record SnapshotView(String html, String hash, String state, String title,
                               boolean frozen, boolean hasSnapshot) {
    }

    public SnapshotView snapshotView(long id) {
        TestReport report = find(id);
        String raw = report.snapshotHtml();
        boolean has = raw != null && !raw.isBlank();
        return new SnapshotView(has ? ReportHtmlSanitizer.sanitize(raw) : "",
                report.snapshotHash(), report.state().name(), report.title(),
                report.frozenAt() != null, has);
    }

    /**
     * 导出。
     *
     * <p>字段顺序与标题都来自快照内模板，所以「先冻结、后改模板、再导出」拿到的仍是
     * 冻结那一刻的字段集合——这是 TPLX-02 的核心判据。
     *
     * <p>v5.22.0 / W72-04：明细来自快照 {@code cases}（**不回查 test_result**）。
     * 对外模式不出命令原文（C5 §3 禁止），内部模式才出。
     */
    public byte[] export(long id, String format) {
        return export(id, format, "external");
    }

    public byte[] export(long id, String format, String mode) {
        ExportGuard guard = exportGuard(id, mode);
        if (!guard.allowed()) {
            // 控制器会先查 exportGuard 并返回结构化错误体；走到这里说明调用方绕过了控制器。
            throw new BusinessException(
                    "REVOKED".equals(guard.state()) || "ARCHIVED".equals(guard.state())
                            ? ErrorCode.CONFLICT : ErrorCode.INVALID_ARGUMENT,
                    guard.message());
        }
        TestReport report = find(id);
        // W69-07：空快照必须拒导，而不是渲染出一份"看着像报告、其实没内容"的文件。
        // 实测对外导出 PDF 恒定 1,521 字节（两个不同报告字节数完全相同）——
        // 根因是 snapshotJson 为空串时 snapshotOf 只判 null 就放行，渲染器拿到空快照
        // 仍会吐出一个只有表头/水印的壳。空白也拒，并给出可操作的下一步。
        String raw = report.snapshotJson();
        if (raw == null || raw.isBlank() || raw.trim().length() <= 2) {
            // `<= 2` 覆盖 `{}`：既不是 null 也不是空串，但同样没有任何字段可导出。
            throw new BusinessException(ErrorCode.CONFLICT,
                    "报告尚未物化快照（无法导出）。请先在报告详情点「冻结」再导出");
        }
        ReportSnapshot snapshot = snapshotOf(report);
        boolean internal = isInternal(mode);
        Map<String, Object> view = renderer.external(snapshot, report.id(), report.title(),
                report.frozenAt() == null ? "" : report.frozenAt().toString(),
                ReportExportService.WATERMARK);
        List<ReportExportService.CaseDetailRow> details = caseDetails(snapshot, internal);
        byte[] bytes;
        if (isPdf(format)) {
            // v5.22.0 / W72-05：PDF 走「快照 HTML → 无头浏览器打印」，不再手写 PDF obj。
            // 原实现用 Helvetica 单字体 + ascii()，中文一律变 '?'（实测 1,521 字节单页空壳），
            // 补 CJK 字体嵌入成本极高且易错，改用与 run 级同一套 PdfRenderer。
            ReportExportService.assertNoSecrets(view, renderer.fieldTitles(snapshot));
            bytes = pdfs.renderView(view, renderer.fieldTitles(snapshot), report.title());
        } else {
            bytes = exports.export(format, view, renderer.fieldTitles(snapshot),
                    details, detailNote(snapshot));
        }
        String ext = ReportExportService.extension(format);
        repository.insertExport(id, ext, "report-" + id + "." + ext, bytes.length,
                ReportExportService.WATERMARK);
        return bytes;
    }

    /**
     * 导出前置判定（v5.22.0 / FR-v5.22-06）。
     *
     * <p>判定**只读快照**，不回查 run / metadata 表：报告比 run 活得久，
     * 回查会在 run 被清理后产生 404——那正是 L-22-01 的口径分裂。
     *
     * <p>返回结构而不是直接抛异常，是因为错误体要带 {@code state} 与 {@code metadataMissing}：
     * 只说「不可导出」，用户不知道该重新冻结还是该补 metadata。
     */
    public ExportGuard exportGuard(long id, String mode) {
        TestReport report = find(id);
        String state = report.state().name();
        if (report.state() == ReportState.REVOKED) {
            return ExportGuard.blocked(409, "CONFLICT",
                    "报告已撤回（" + state + "），不可导出；如需对外交付请重新建报告并冻结",
                    state, List.of());
        }
        if (report.state() == ReportState.ARCHIVED) {
            return ExportGuard.blocked(409, "CONFLICT",
                    "报告已归档（" + state + "），只读留档不可导出", state, List.of());
        }
        if (isInternal(mode)) {
            return ExportGuard.ok();
        }
        ReportSnapshot snapshot = snapshotOf(report);
        if (renderer.flag(snapshot, "exportable", false)) {
            return ExportGuard.ok();
        }
        List<String> missing = renderer.strings(snapshot, "metadataMissing");
        return ExportGuard.blocked(400, "INVALID_ARGUMENT",
                "报告元数据缺项，不能对外导出"
                        + (missing.isEmpty() ? "" : "：" + String.join(" / ", missing))
                        + "；补齐后重新冻结，或改用 mode=internal 导出内部版",
                state, missing);
    }

    /**
     * 导出前置判定的结果。
     *
     * @param allowed         是否放行
     * @param status          HTTP 状态（放行时 200；撤回/归档 409；metadata 缺项 400）
     * @param codeName        统一响应体里的 codeName
     * @param message         给人看的原因（含下一步动作）
     * @param state           报告状态（REVOKED / ARCHIVED / DRAFT / FROZEN）
     * @param metadataMissing 缺失的 metadata 键（仅 400 时有值）
     */
    public record ExportGuard(boolean allowed, int status, String codeName, String message,
                              String state, List<String> metadataMissing) {

        static ExportGuard ok() {
            return new ExportGuard(true, 200, "OK", "", "", List.of());
        }

        static ExportGuard blocked(int status, String codeName, String message, String state,
                                   List<String> missing) {
            return new ExportGuard(false, status, codeName, message, state, List.copyOf(missing));
        }
    }

    private static boolean isInternal(String mode) {
        return mode != null && "internal".equalsIgnoreCase(mode.trim());
    }

    private static boolean isPdf(String format) {
        return format != null && "pdf".equalsIgnoreCase(format.trim());
    }

    /**
     * 快照明细 → 导出行。
     *
     * <p>对外模式把 {@code commandText} 置 null（C5 §3 禁止对外文件出现命令原文），
     * {@code failureMsg} 过 {@link ReportViewRenderer#scrub}；内部模式原样输出，
     * 因为内部件的用途就是排查，脱敏会把定位线索一起抹掉。
     */
    private List<ReportExportService.CaseDetailRow> caseDetails(ReportSnapshot snapshot,
                                                                boolean internal) {
        List<Map<String, Object>> rows = renderer.caseRows(snapshot);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ReportExportService.CaseDetailRow> out = new java.util.ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String failure = text(row.get("failureMsg"));
            String command = text(row.get("commandText"));
            out.add(new ReportExportService.CaseDetailRow(
                    text(row.get("caseCode")),
                    text(row.get("verdict")),
                    row.get("durationMs") instanceof Number n ? n.longValue() : null,
                    internal ? failure : scrubOrEmpty(failure),
                    internal ? command : null));
        }
        return out;
    }

    /** 明细为空时写进 sheet 的说明；{@code null} = 不出明细 sheet（不该发生，兜底用）。 */
    private String detailNote(ReportSnapshot snapshot) {
        List<Map<String, Object>> rows = renderer.caseRows(snapshot);
        if (rows == null) {
            return "该报告冻结时未记录用例明细（历史版本快照）；重新冻结后即包含逐条明细。";
        }
        if (rows.isEmpty()) {
            return "本次执行没有用例级记录（采集成功但结果为空）。";
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String scrubOrEmpty(String value) {
        return value == null || value.isBlank() ? "" : ReportViewRenderer.scrub(value);
    }

    public List<TestReportExport> exports(long id) {
        find(id);
        return repository.exports(id);
    }

    public int purge(int retentionDays) {
        if (retentionDays < 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "保留天数必须 >= 1");
        }
        return repository.purgeExportsBefore(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
    }

    private ReportSnapshot snapshotOf(TestReport report) {
        if (report.snapshotJson() == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "报告尚未物化快照");
        }
        return new ReportSnapshot(report.snapshotJson(), report.snapshotHtml(),
                report.snapshotHash(), report.version(), report.sourceSummary());
    }

    /**
     * v5.14.0 / W64-11：按该 run 的内核做分级模板选择（项目 kernel 匹配 → 平台 kernel
     * 匹配 → 平台 any → 任意平台默认）。接口用 default 方法回落旧的两级实现，
     * 所以既有伪仓储 / 单测不受影响；真实 JDBC 实现会覆盖它。
     */
    private ReportTemplate defaultTemplate(long projectId, String runId) {
        return templates.findDefaultForRun(projectId, runId).orElse(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }
}
