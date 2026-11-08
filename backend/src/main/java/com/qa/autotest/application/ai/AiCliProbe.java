package com.qa.autotest.application.ai;

import com.qa.autotest.application.kernel.EnvironmentProbe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * CLI 探测与降级（AIX-02 / C6 / FR-AI-07）。
 *
 * <p>只查 PATH 与可执行位，**不发起任何模型调用**；探测结果决定面板能否进 L2/L3，
 * 没有 CLI 时 L1 静态建议照常可用，绝不报错阻断主功能（R6）。
 *
 * <p>FR-AI-07 接线后：{@code anyAvailable} 还要求 {@code FEATURE_AI_REAL_CLI} 显式开启，
 * 且本机存在候选 CLI 或 {@code AI_CLI_PATH} 注入的可执行文件；否则一律 DEGRADED。
 */
@Service
public class AiCliProbe {

    private static final List<String> CANDIDATES = AiCliAdapter.CANDIDATES;

    public Map<String, Object> probe() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean anyOnPath = false;
        for (String name : CANDIDATES) {
            boolean found = EnvironmentProbe.onPath(name);
            result.put(name, found);
            anyOnPath |= found;
        }
        boolean injected = AiCliAdapter.hasConfiguredExecutable();
        boolean realCli = AiFeatureGate.realCliFeatureEnabled();
        boolean anyAvailable = (anyOnPath || injected) && realCli;
        result.put("anyOnPath", anyOnPath || injected);
        result.put("realCliEnabled", realCli);
        result.put("cliPathInjected", injected);
        result.put("anyAvailable", anyAvailable);
        result.put("maxLevel", anyAvailable ? "L3" : "L1");
        result.put("mode", anyAvailable ? "NORMAL" : "DEGRADED");
        result.put("nonInteractiveOnly", true);
        return result;
    }

    public boolean available() {
        return Boolean.TRUE.equals(probe().get("anyAvailable"));
    }

    /** 首选 CLI：探测命中的第一个候选，缺省 claude。 */
    public String primaryCli() {
        Map<String, Object> result = probe();
        for (String name : CANDIDATES) {
            if (Boolean.TRUE.equals(result.get(name))) {
                return name;
            }
        }
        return "claude";
    }
}
