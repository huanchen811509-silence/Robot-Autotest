package com.qa.autotest.application.report;

import com.qa.autotest.application.kernel.pytest.PytestResultNormalizer;
import com.qa.autotest.domain.kernel.NormalizedCaseResult;
import com.qa.autotest.domain.kernel.NormalizedRun;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把框架命令索引补进归一结果（v5.6.0 / FR-v5.6-21）。
 *
 * <p><b>为什么不在归一器里做</b>：归一器在 pytest 适配包内，而门禁
 * {@code check_kernel_decoupling} 禁止适配层反向依赖 {@code application.report}。
 * 命令索引要复用 {@link CaseCommandExtractor}（脱敏/截断规则的唯一定义处），
 * 所以补录动作留在报告层，由调用方（控制器）在归一之后调一次——方向对了，
 * 适配层也就不用为了「顺手取个命令」被塞进报告域的知识。
 *
 * <p>幂等：已有命令（{@code PYTEST_MARK} / 显式标记）的行不覆盖——
 * 显式标记比「索引里第一条」更准确地表达了用例作者意图。
 */
@Service
public class PytestCommandEnricher {

    /**
     * 补录命令。
     *
     * @param commandRoot 框架产物根 {@code <repo>/artifacts/<run_id>}；{@code null} 时原样返回
     * @param normalized  归一结果（含 nodeId → 脚本路径映射）
     * @return 补录后的归一结果；无命令可补时返回入参本身（引用相等，调用方据此免拷贝）
     */
    public PytestResultNormalizer.NormalizeResult enrich(
            Path commandRoot, PytestResultNormalizer.NormalizeResult normalized) {
        if (commandRoot == null || normalized == null || normalized.run() == null) {
            return normalized;
        }
        Map<String, String> scriptPathByNode = new HashMap<>();
        for (PytestResultNormalizer.CaseMapping mapping : normalized.mappings()) {
            if (mapping.scriptPath() != null && !mapping.scriptPath().isBlank()) {
                scriptPathByNode.put(mapping.nodeId(), mapping.scriptPath());
            }
        }
        List<NormalizedCaseResult> enriched = new ArrayList<>();
        boolean changed = false;
        for (NormalizedCaseResult one : normalized.run().cases()) {
            if (!NormalizedCaseResult.NO_COMMAND.equals(one.commandText())) {
                enriched.add(one);
                continue;
            }
            CaseCommandExtractor.Extracted command = lookup(commandRoot,
                    scriptPathByNode.get(one.nodeId()), one.nodeId());
            if (!command.hasCommand()) {
                enriched.add(one);
                continue;
            }
            changed = true;
            enriched.add(new NormalizedCaseResult(one.nodeId(), one.name(), one.status(),
                    one.durationSeconds(), one.message(), command.text(), command.source(),
                    command.exitCode()));
        }
        if (!changed) {
            return normalized;
        }
        NormalizedRun run = new NormalizedRun(normalized.run().kernel(), normalized.run().runId(),
                enriched, normalized.run().metadata());
        return new PytestResultNormalizer.NormalizeResult(run, normalized.mappings(),
                normalized.unbound(), normalized.allSkipped(), normalized.sidecar());
    }

    /**
     * 候选 nodeid 的顺序很关键：框架目录名是 **pytest nodeid**
     * （斜杠路径 + {@code .py} + {@code ::} 函数名），而 junit 的 {@code classname} 是**点分**的，
     * 直接拿 junit 名去拼目录一条都匹配不上。所以先用绑定脚本路径拼出真实 nodeid，
     * 再用原始 nodeid 兜底。
     */
    private static CaseCommandExtractor.Extracted lookup(Path commandRoot, String scriptPath,
                                                         String nodeId) {
        List<String> candidates = new ArrayList<>();
        if (scriptPath != null && !scriptPath.isBlank()) {
            candidates.add(scriptPath.replace('\\', '/') + "::" + functionOf(nodeId));
        }
        if (nodeId != null && !nodeId.isBlank()) {
            candidates.add(nodeId.replace('.', '/') + ".py::" + functionOf(nodeId));
            candidates.add(nodeId);
        }
        return PytestCommandIndex.lookup(commandRoot, candidates);
    }

    /** {@code tests.a.b.test_x::test_y[1]} → {@code test_y[1]}。 */
    private static String functionOf(String nodeId) {
        if (nodeId == null) {
            return "";
        }
        int index = nodeId.lastIndexOf("::");
        return index < 0 ? nodeId : nodeId.substring(index + 2);
    }
}
