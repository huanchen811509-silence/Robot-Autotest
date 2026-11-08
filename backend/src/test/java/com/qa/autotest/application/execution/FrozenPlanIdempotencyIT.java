package com.qa.autotest.application.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.qa.autotest.application.execution.FrozenPlanService.FrozenPlanRequest;
import com.qa.autotest.application.execution.FrozenPlanService.FrozenPlanStore;
import com.qa.autotest.application.execution.FrozenPlanService.FrozenRun;
import com.qa.autotest.common.BusinessException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * EVID-01：冻结计划 SHA / 幂等提交 / 未知状态只读找回。
 * 纯逻辑单测，不连数据库；持久化用内存 fake repository。类名带 IT 只是命名约定，
 * 刻意不加 it 标签注解，确保全量 mvn test（excluded.groups=it）也会执行它。
 */
class FrozenPlanIdempotencyIT {

    private static final long PROJECT = 7L;
    private static final Long SELECTION = 42L;
    private static final int ROUND = 1;
    private static final List<String> SUITES = List.of("suites/a.robot", "suites/b.robot");
    private static final String PAYLOAD = "{\"cases\":[1,2]}";

    private static final String FROZEN_SRC =
            "src/main/java/com/qa/autotest/application/execution/FrozenPlanService.java";
    private static final String RUN_EXEC_SRC =
            "src/main/java/com/qa/autotest/application/execution/RunExecutionService.java";
    private static final String MIGRATION =
            "db/migration/V11_13__run_frozen_round.sql";

    /** 内存 fake：模拟 uk_run_idempotency(project_id, idempotency_key) 唯一键 + 先查后写。 */
    private static final class MemoryStore implements FrozenPlanStore {
        final Map<String, FrozenRun> byKey = new HashMap<>();
        final Map<String, FrozenRun> byRunId = new HashMap<>();
        int inserts;

        private static String key(long projectId, String k) {
            return projectId + "|" + k;
        }

        @Override
        public Optional<FrozenRun> findByIdempotencyKey(long projectId, String idempotencyKey) {
            return Optional.ofNullable(byKey.get(key(projectId, idempotencyKey)));
        }

        @Override
        public Optional<FrozenRun> findByRunId(long projectId, String runId) {
            return Optional.ofNullable(byRunId.get(key(projectId, runId)));
        }

        @Override
        public FrozenRun insert(FrozenRun draft) {
            String k = key(draft.projectId(), draft.idempotencyKey());
            if (byKey.containsKey(k)) {
                throw new IllegalStateException("uk_run_idempotency 冲突");
            }
            inserts++;
            byKey.put(k, draft);
            byRunId.put(key(draft.projectId(), draft.runId()), draft);
            return draft;
        }

        @Override
        public int insertCount() {
            return inserts;
        }
    }

    private FrozenPlanRequest request(String key, String sha) {
        return new FrozenPlanRequest(PROJECT, key, sha, SELECTION, SUITES, ROUND, PAYLOAD);
    }

    /** 请求方与服务端共用「同一规则」生成 freezeSha256。 */
    private String serverSha() {
        return FrozenPlanService.computePlanSha256(PROJECT, SELECTION, SUITES, ROUND, PAYLOAD);
    }

    @Test
    void 同key两次提交只产生1个run_第二次不新增不重发命令() {
        MemoryStore store = new MemoryStore();
        List<FrozenRun> sent = new ArrayList<>();
        FrozenPlanService service = new FrozenPlanService(store, sent::add);

        FrozenRun first = service.submit(request("KEY-1", serverSha()));
        FrozenRun second = service.submit(request("KEY-1", serverSha()));

        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(store.insertCount()).isEqualTo(1);
        assertThat(store.byKey).hasSize(1);
        assertThat(sent).hasSize(1);          // 幂等命中第二次不重发命令
    }

    @Test
    void 冻结计划SHA不符被明确拒绝且不落run() {
        MemoryStore store = new MemoryStore();
        FrozenPlanService service = new FrozenPlanService(store);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submit(request("KEY-SHA", "deadbeef".repeat(8))));
        assertThat(ex.getMessage()).contains("SHA", "不符", "拒绝", "不落 run");
        assertThat(store.insertCount()).isZero();
    }

    @Test
    void 计划材料被篡改时按同一规则复算的SHA不符被拒() {
        MemoryStore store = new MemoryStore();
        FrozenPlanService service = new FrozenPlanService(store);
        FrozenPlanRequest tampered = new FrozenPlanRequest(PROJECT, "KEY-T", serverSha(),
                SELECTION, List.of("suites/a.robot", "suites/evil.robot"), ROUND, PAYLOAD);
        assertThrows(BusinessException.class, () -> service.submit(tampered));
        assertThat(store.insertCount()).isZero();
        // 同一规则：RunExecutionService 的委托入口与 FrozenPlanService 结果一致
        assertThat(RunExecutionService.computePlanSha256(PROJECT, SELECTION, SUITES, ROUND, PAYLOAD))
                .isEqualTo(serverSha());
    }

    @Test
    void 未知状态只读找回_不新增run_不重发命令() {
        MemoryStore store = new MemoryStore();
        List<FrozenRun> sent = new ArrayList<>();
        FrozenPlanService service = new FrozenPlanService(store, sent::add);
        FrozenRun created = service.submit(request("KEY-R", serverSha()));
        int insertsBefore = store.insertCount();
        int sentBefore = sent.size();

        Optional<FrozenRun> found = service.recover(PROJECT, created.runId());
        assertThat(found).isPresent();
        assertThat(found.get().runId()).isEqualTo(created.runId());
        assertThat(service.recover(PROJECT, "RUN-UNKNOWN")).isEmpty();
        assertThat(store.insertCount()).isEqualTo(insertsBefore);   // 找回不新增 run
        assertThat(sent).hasSize(sentBefore);                       // 找回不重发命令
    }

    @Test
    void 找回分支源码不含INSERT与执行命令() throws Exception {
        String frozen = source(FROZEN_SRC);
        int at = frozen.indexOf("public Optional<FrozenRun> recover(");
        assertThat(at).isPositive();
        assertThat(frozen.substring(at).toLowerCase(Locale.ROOT))
                .doesNotContain("insert", "execute", "dispatch", "launch", "upsert",
                        "processbuilder", "transition", ".create(", "commandSender");

        String runExec = source(RUN_EXEC_SRC);
        int at2 = runExec.indexOf("public RunRecord recoverUnknownRun(");
        assertThat(at2).isPositive();
        assertThat(runExec.substring(at2).toLowerCase(Locale.ROOT))
                .doesNotContain("insert", "execute", "dispatch", "launch", "upsert",
                        "processbuilder", "transition", ".create(", "commandSender");
    }

    @Test
    void 迁移含幂等唯一键与冻结列() throws Exception {
        String migration = source(MIGRATION);
        assertThat(migration).contains("uk_run_idempotency", "freeze_sha256",
                "idempotency_key", "round_no", "source_selection_id", "VARCHAR(64)");
        assertThat(migration).contains("`project_id`, `idempotency_key`");
        assertThat(migration.toLowerCase(Locale.ROOT))
                .doesNotContain("update `run`", "backfill");
    }

    private static Path repoFile(String relative) {
        Path fromBackend = Path.of("..", relative);
        return Files.isRegularFile(fromBackend) ? fromBackend : Path.of(relative);
    }

    private static String source(String relative) throws Exception {
        return Files.readString(repoFile(relative));
    }
}
