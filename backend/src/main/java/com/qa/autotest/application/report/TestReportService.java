package com.qa.autotest.application.report;

import com.qa.autotest.application.repository.ReportTemplateRepository;
import com.qa.autotest.application.repository.TestReportRepository;
import com.qa.autotest.application.repository.TestSetRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
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

    public TestReportService(TestReportRepository repository, ReportSnapshotService snapshots,
                             ReportViewRenderer renderer, ReportExportService exports,
                             ReportQueryService queries, ReportTemplateRepository templates,
                             TestSetRepository caseSets) {
        this.repository = repository;
        this.snapshots = snapshots;
        this.renderer = renderer;
        this.exports = exports;
        this.queries = queries;
        this.templates = templates;
        this.caseSets = caseSets;
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
        ReportSnapshot snapshot = snapshots.build(queries.detail(projectId, runId), version,
                defaultTemplate(projectId));
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
        ReportSnapshot snapshot = snapshots.build(
                queries.detail(report.projectId(), report.runId()), version,
                defaultTemplate(report.projectId()));
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

    /** 报告是按哪个模板第几版冻结的；历史快照没有模板回指时 `bound=false`。 */
    public ReportViewRenderer.TemplateRef template(long id) {
        return renderer.templateRef(snapshotOf(find(id)));
    }

    /**
     * 导出。
     *
     * <p>字段顺序与标题都来自快照内模板，所以「先冻结、后改模板、再导出」拿到的仍是
     * 冻结那一刻的字段集合——这是 TPLX-02 的核心判据。
     */
    public byte[] export(long id, String format) {
        TestReport report = find(id);
        ReportSnapshot snapshot = snapshotOf(report);
        Map<String, Object> view = renderer.external(snapshot, report.id(), report.title(),
                report.frozenAt() == null ? "" : report.frozenAt().toString(),
                ReportExportService.WATERMARK);
        byte[] bytes = exports.export(format, view, renderer.fieldTitles(snapshot));
        String ext = ReportExportService.extension(format);
        repository.insertExport(id, ext, "report-" + id + "." + ext, bytes.length,
                ReportExportService.WATERMARK);
        return bytes;
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

    /** 未指定模板一律回落 DEFAULT（项目级默认 → 平台级内置），都没有时按全量白名单渲染。 */
    private ReportTemplate defaultTemplate(long projectId) {
        return templates.findDefault(projectId).orElse(null);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }
}
