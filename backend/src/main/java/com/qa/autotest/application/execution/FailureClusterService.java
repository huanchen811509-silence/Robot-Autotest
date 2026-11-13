package com.qa.autotest.application.execution;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.FailureResultRepository;
import com.qa.autotest.application.repository.RunRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.execution.FailureClass;
import com.qa.autotest.domain.execution.FailureClusterKey;
import com.qa.autotest.domain.iam.PermissionAction;
import com.qa.autotest.domain.iam.PermissionResource;
import com.qa.autotest.domain.execution.RunRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 失败聚类（ORCH-03，v2.1）。
 *
 * <p>三件事分开做，避免互相污染：
 * <ol>
 *   <li><b>分类</b>复用既有 {@link FailureClassifier}（CANCELED &gt; ENV &gt; INFRA &gt; ASSERT），
 *       本类不重写规则，只把用例级失败文本喂进去；</li>
 *   <li><b>簇键</b>由 {@link FailureClusterKey} 归一化，纯确定性；</li>
 *   <li><b>排序</b>固定为「类别枚举序 → 簇条数倒序 → 簇键字典序」，
 *       不用 HashMap 迭代序、不按时间，保证同一输入两次输出逐字节一致。</li>
 * </ol>
 *
 * <p>为什么不落库：簇是视图。归一化规则一改，历史簇就该重算；落库会让新旧规则算出的簇并存。
 */
@Service
public class FailureClusterService {

    /** 每簇最多展示几条样例：超过 3 条在表格里已经看不清，且用户只需要判断「是不是同一回事」。 */
    private static final int SAMPLE_LIMIT = 3;

    /**
     * 展示名上限（字符）。取 300 是因为常见断言消息在 100~200 字符，
     * 再长的多行堆栈只解析首行，正常不会触发；设上限只为防单行超长污染响应体。
     */
    private static final int DISPLAY_MAX_LENGTH = 300;

    private final FailureResultRepository results;
    private final FailureClassifier classifier;
    private final AccessControlService access;
    private final CaseRunService caseRuns;
    private final RunRepository runs;

    public FailureClusterService(FailureResultRepository results,
                                 FailureClassifier classifier,
                                 AccessControlService access,
                                 CaseRunService caseRuns,
                                 RunRepository runs) {
        this.results = results;
        this.classifier = classifier;
        this.access = access;
        this.caseRuns = caseRuns;
        this.runs = runs;
    }

    /**
     * 聚一轮的失败。
     *
     * @param runId 运行业务 ID（{@code run.run_id}）
     */
    public FailureClusterView cluster(long projectId, String runId) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        if (runId == null || runId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "runId 不能为空");
        }

        List<FailureResultRepository.FailureRow> rows = results.failedRows(projectId, runId);
        FailureResultRepository.RunFailureContext context = results.runContext(projectId, runId);

        return new FailureClusterView(runId, rows.size(),
                context == null ? null : context.runStatus(),
                context == null ? null : context.failureClass(),
                classGroups(rows));
    }

    /**
     * 跨 run 聚类：取最近 N 轮里所有失败用例，按同一套归一化规则合并。
     *
     * <p>为什么按「最近 N 轮」而不是时间窗：时间窗（如 7 天）在低频项目里会只剩 1~2 轮，
     * 在压测期又会挤进上百轮，样本量不可控；按轮数取，用户能直接判断「连续 3 轮都栽在同一处」。
     *
     * <p>返回的 {@code runId} 是作用域标记 {@code recent:N}——它不是真实运行 ID，
     * 不能被拿去调用按簇重跑（重跑仍要求单轮 runId）。这是故意的：跨轮簇合并了多轮用例，
     * 单独重跑某一轮会破坏统计口径。
     *
     * @param runs 最近几轮，1..20；只统计已结束的 run（page 默认按创建时间倒序）
     */
    public FailureClusterView clusterRecent(long projectId, int runs) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.READ);
        if (runs < 1 || runs > 20) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "runs 取值范围 1..20");
        }

        List<RunRecord> recent = this.runs.page(projectId, null, 1, runs).items();
        List<FailureResultRepository.FailureRow> merged = new ArrayList<>();
        for (RunRecord run : recent) {
            merged.addAll(results.failedRows(projectId, run.runId()));
        }
        return new FailureClusterView("recent:" + runs, merged.size(), null, null,
                classGroups(merged));
    }

    /** 把失败行按「类别 → 簇键」归组并排序；单轮与跨轮共用，避免两套统计口径。 */
    private List<FailureClusterView.ClassGroup> classGroups(
            List<FailureResultRepository.FailureRow> rows) {
        Map<FailureClass, Map<String, List<FailureResultRepository.FailureRow>>> grouped =
                new EnumMap<>(FailureClass.class);
        final int total = rows.size();
        for (FailureResultRepository.FailureRow row : rows) {
            FailureClass failureClass = classify(row.failureMsg());
            String key = FailureClusterKey.of(row.failureMsg());
            grouped.computeIfAbsent(failureClass, ignored -> new TreeMap<>())
                    .computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(row);
        }

        List<FailureClusterView.ClassGroup> classGroups = new ArrayList<>();
        for (FailureClass failureClass : FailureClass.values()) {
            Map<String, List<FailureResultRepository.FailureRow>> clusters = grouped.get(failureClass);
            if (clusters == null || clusters.isEmpty()) {
                continue;
            }
            int classCount = clusters.values().stream().mapToInt(List::size).sum();
            List<FailureClusterView.Cluster> views = new ArrayList<>(clusters.size());
            clusters.entrySet().stream()
                    .sorted(clusterOrder())
                    .forEach(entry -> views.add(toCluster(entry.getKey(), entry.getValue(), total)));
            classGroups.add(new FailureClusterView.ClassGroup(failureClass.name(), classCount,
                    ratio(classCount, total), views));
        }
        return classGroups;
    }

    /**
     * 按簇重跑：把该簇的用例 id 交给既有 {@link CaseRunService}，
     * 安全级拦截（L2 / LX）由它判，本类不做第二套判断。
     */
    public RunRecord rerun(long projectId, String runId, String clusterKey) {
        access.bindProject(projectId);
        access.require(PermissionResource.EXECUTION, PermissionAction.EXECUTE);
        RunRecord source = runs.findByRunId(projectId, runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "运行不存在: " + runId));
        FailureClusterView view = cluster(projectId, runId);
        for (FailureClusterView.ClassGroup group : view.classes()) {
            for (FailureClusterView.Cluster cluster : group.clusters()) {
                if (cluster.key().equals(clusterKey)) {
                    if (cluster.caseIds().isEmpty()) {
                        throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                                "该簇没有关联用例，无法重跑: " + clusterKey);
                    }
                    RunRecord created = caseRuns.run(new CaseRunService.CaseRunCommand(projectId,
                            cluster.caseIds(), null, List.of(), null, null, null, null, null));
                    // N8：把「重跑自哪一轮」写回 DB，attempt_no 在原运行基础上 +1。
                    // 不写的话，运行详情里 retryOfRunId 永远是空，簇重跑看起来像一次全新运行，
                    // 「同一簇连试三轮仍失败」这种判断就失去依据。
                    int attemptNo = Math.max(source.attemptNo(), 1) + 1;
                    runs.markAttempt(created.runId(), attemptNo, runId);
                    return runs.findByRunId(projectId, created.runId()).orElse(created);
                }
            }
        }
        throw new BusinessException(ErrorCode.NOT_FOUND, "簇不存在: " + clusterKey);
    }

    /** 用例级分类：只喂失败文本，产物视为可用（用例行没有产物上下文）。 */
    private FailureClass classify(String failureMsg) {
        return classifier.classify(new FailureClassifier.FailureInput(
                false, null, failureMsg, null, null, null, true));
    }

    private static FailureClusterView.Cluster toCluster(String key,
                                                        List<FailureResultRepository.FailureRow> rows,
                                                        int total) {
        List<FailureClusterView.Sample> samples = rows.stream()
                .limit(SAMPLE_LIMIT)
                .map(row -> new FailureClusterView.Sample(row.caseId(), row.caseCode(), row.caseTitle()))
                .toList();
        List<Long> caseIds = rows.stream()
                .map(FailureResultRepository.FailureRow::caseId)
                .filter(java.util.Objects::nonNull)
                .toList();
        return new FailureClusterView.Cluster(key, displayOf(rows, key), rows.size(),
                ratio(rows.size(), total), samples, caseIds);
    }

    /**
     * 展示名取首个样本失败消息的首行（FR-v5.6-22）。
     *
     * <p>为什么用首行而不是整段：失败消息常带多行堆栈，首行才是「同一回事」的判据；
     * 全塞进表格会把行高撑到无法扫读。为什么不用指纹：指纹为了聚合稳定做了数字抹除与截断，
     * 用户看到 {@code ... <n> != <n>} 并不知道哪条断言炸了。
     *
     * @param key 簇指纹，原文为空时回落，避免界面出现空白标题
     */
    private static String displayOf(List<FailureResultRepository.FailureRow> rows, String key) {
        for (FailureResultRepository.FailureRow row : rows) {
            String message = row.failureMsg();
            if (message == null) {
                continue;
            }
            int lineEnd = message.indexOf('\n');
            String firstLine = (lineEnd < 0 ? message : message.substring(0, lineEnd)).strip();
            if (!firstLine.isEmpty()) {
                return firstLine.length() > DISPLAY_MAX_LENGTH
                        ? firstLine.substring(0, DISPLAY_MAX_LENGTH) + "…"
                        : firstLine;
            }
        }
        return key;
    }

    /** 簇排序：条数倒序，同条数按簇键字典序（稳定，不依赖插入顺序）。 */
    private static Comparator<Map.Entry<String, List<FailureResultRepository.FailureRow>>> clusterOrder() {
        return Comparator
                .<Map.Entry<String, List<FailureResultRepository.FailureRow>>>comparingInt(
                        entry -> -entry.getValue().size())
                .thenComparing(entry -> entry.getKey(), FailureClusterKey::compare);
    }

    /** 占比保留 4 位小数：够表达 1/3 与 1/10000，又不引入浮点尾差。 */
    private static double ratio(int part, int total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round(part * 10000.0 / total) / 10000.0;
    }
}
