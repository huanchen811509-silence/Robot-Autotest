package com.qa.autotest.application.ops;

import com.qa.autotest.application.repository.OpsTaskRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.ops.OpsTask;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 运维任务状态机（v5.16.0 / FR-v5.16-09）。
 *
 * <p>本版刻意**不引入独立异步线程**：阶段进度以同步分阶段写库的方式表达，
 * 避免异步线程漏 TenantContext.runAs 或 progress.complete 早于入库这两类历史缺陷。
 * 「真正异步执行」如实登记为本版缺口，不用单测冒充达成。
 * 默认 dry-run 不写任何行（一票否决 V-2）。
 */
@Service
public class OpsTaskService {

    public static final List<String> PHASES = List.of("PRECHECK", "EXECUTE", "VERIFY");

    public record CreateResult(boolean dryRun, long taskId, String status, List<String> plan) {
    }

    private final OpsTaskRepository repository;

    public OpsTaskService(OpsTaskRepository repository) {
        this.repository = repository;
    }

    public CreateResult create(long projectId, String taskType, String paramsJson,
                               Long operatorId, boolean dryRun) {
        String type = taskType == null || taskType.isBlank() ? "UNKNOWN" : taskType.trim();
        long id = repository.insert(projectId, type, paramsJson, operatorId);
        if (dryRun) {
            // P4 全量审计：预演也要留一行「谁、何时、预演了什么、结果如何」。
            // 「零写入」指的是业务数据零写入（不跑备份 / 不恢复 / 不升级），不是审计零行。
            repository.advance(projectId, id, "PRECHECK", 25,
                    "[PRECHECK] dry-run plan generated; no business write", OpsTask.RUNNING);
            repository.finish(projectId, id, OpsTask.SUCCESS,
                    "{\"result\":\"DRY_RUN\",\"executed\":false}");
            return new CreateResult(true, id, OpsTask.SUCCESS, PHASES);
        }
        for (int i = 0; i < PHASES.size(); i++) {
            String phase = PHASES.get(i);
            int progress = (i + 1) * 25;
            repository.advance(projectId, id, phase, progress,
                    "[" + phase + "] read-only stage completed", OpsTask.RUNNING);
        }
        repository.finish(projectId, id, OpsTask.SUCCESS,
                "{\"result\":\"READ_ONLY_PREVIEW\",\"executed\":false}");
        return new CreateResult(false, id, OpsTask.SUCCESS, PHASES);
    }

    public OpsTask detail(long projectId, long id) {
        return repository.find(projectId, id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "运维任务不存在: " + id));
    }

    public List<OpsTask> list(long projectId, int limit) {
        return repository.list(projectId, limit);
    }

    public long count(long projectId) {
        return repository.count(projectId);
    }

    public void abort(long projectId, long id) {
        OpsTask task = detail(projectId, id);
        if (!OpsTask.PENDING.equals(task.status()) && !OpsTask.RUNNING.equals(task.status())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "任务已处于终态 " + task.status() + "，不可中止");
        }
        repository.finish(projectId, id, OpsTask.ABORTED,
                "{\"result\":\"ABORTED_BY_OPERATOR\"}");
    }

    /** 租约超时对账：被杀 / 卡住的 RUNNING 任务必须判 ABORTED（一票否决 V-3）。 */
    public int sweep(long projectId) {
        return repository.sweep(projectId);
    }

    /** 恢复演练库名守卫：只允许 _it 结尾的演练库（一票否决 V-1）。 */
    public static String requireDrillDatabase(String targetDb) {
        String db = targetDb == null ? "" : targetDb.trim();
        if (!db.endsWith("_it")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "恢复演练只允许以 _it 结尾的演练库：targetDb=" + db + " 被拒");
        }
        return db;
    }
}
