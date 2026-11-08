package com.qa.autotest.application.execution;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * EVID-01：冻结计划提交与未知状态只读找回。
 *
 * <p>三条判据：
 * <ul>
 *   <li>冻结计划 SHA：提交时把请求携带的 {@code freezeSha256} 与服务端按同一规则
 *       （{@link #computePlanSha256}）复算的计划 SHA 比对，不符明确拒绝且<b>不落 run</b>；</li>
 *   <li>幂等提交：同一 {@code (projectId, idempotencyKey)} 先查后写，命中即返回既有 run，
 *       第二次不新增、也不重发命令；DB 侧由 {@code uk_run_idempotency} 唯一键兜底；</li>
 *   <li>只读找回：给定未知/异常状态 RUN_ID 只查询，不新增 run、不重发命令
 *       （{@link #recover} 方法体刻意不含任何写/执行调用，供静态断言锁定）。</li>
 * </ul>
 *
 * <p>纯逻辑类，不连数据库；持久化由 {@link FrozenPlanStore} 抽象，单测用内存 fake 替身。
 */
public class FrozenPlanService {

    // ---- 不可变数据结构与仓储抽象置于前部，使 recover 成为类末尾成员，便于静态断言锁定 ----

    /** 内存 / JDBC 仓储抽象：唯一键先查后写。 */
    public interface FrozenPlanStore {
        Optional<FrozenRun> findByIdempotencyKey(long projectId, String idempotencyKey);
        Optional<FrozenRun> findByRunId(long projectId, String runId);
        FrozenRun insert(FrozenRun draft);
        int insertCount();
    }

    public record FrozenPlanRequest(
            long projectId,
            String idempotencyKey,
            String freezeSha256,
            Long sourceSelectionId,
            List<String> suitePaths,
            int roundNo,
            String payloadJson) {
    }

    public record FrozenRun(
            String runId,
            long projectId,
            String idempotencyKey,
            String freezeSha256,
            int roundNo,
            String state) {
    }

    private final FrozenPlanStore store;
    /** 新 run 派发命令的通道；幂等命中与只读找回都不得触发它。 */
    private final Consumer<FrozenRun> commandSender;

    public FrozenPlanService(FrozenPlanStore store) {
        this(store, run -> { });
    }

    public FrozenPlanService(FrozenPlanStore store, Consumer<FrozenRun> commandSender) {
        this.store = store;
        this.commandSender = commandSender;
    }

    /**
     * 确定性冻结计划 SHA-256：同一份计划材料每次复算结果相同。
     * 请求方与服务端必须用这「同一规则」生成 / 校验 freezeSha256。
     */
    public static String computePlanSha256(long projectId, Long sourceSelectionId,
                                           List<String> suitePaths, int roundNo,
                                           String payloadJson) {
        List<String> suites = suitePaths == null ? List.of() : suitePaths;
        String material = projectId + "|" + sourceSelectionId + "|"
                + String.join(",", suites) + "|" + roundNo + "|"
                + (payloadJson == null ? "" : payloadJson);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 幂等提交：SHA 不符明确拒绝；命中 key 返回既有 run（先查后写）。 */
    public FrozenRun submit(FrozenPlanRequest request) {
        String serverSha = computePlanSha256(request.projectId(), request.sourceSelectionId(),
                request.suitePaths(), request.roundNo(), request.payloadJson());
        if (!serverSha.equals(request.freezeSha256())) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "冻结计划 SHA 不符，拒绝提交且不落 run：服务端=" + serverSha
                            + "，请求=" + request.freezeSha256());
        }
        String key = request.idempotencyKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "idempotencyKey 不能为空");
        }
        Optional<FrozenRun> existing = store.findByIdempotencyKey(request.projectId(), key);
        if (existing.isPresent()) {
            return existing.get();
        }
        FrozenRun created = store.insert(new FrozenRun(
                "RUN-FROZEN-" + UUID.randomUUID(), request.projectId(), key,
                serverSha, request.roundNo(), "QUEUED"));
        commandSender.accept(created);
        return created;
    }

    /** 只读找回：只查询，不新增 run、不重发命令。此方法体刻意不含任何写 / 执行调用。 */
    public Optional<FrozenRun> recover(long projectId, String runId) {
        return store.findByRunId(projectId, runId);
    }
}
