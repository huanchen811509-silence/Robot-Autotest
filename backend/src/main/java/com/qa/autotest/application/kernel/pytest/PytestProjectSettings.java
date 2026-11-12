package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.repository.ProjectSettingRepository;
import java.nio.file.Path;
import java.util.Map;

/**
 * pytest 工程配置项（FR-v5.2-02 / 验收 B4–B6）。
 *
 * <p>全部落在既有 {@code project_setting} KV 表，键统一前缀 {@code pytest.}。
 * 不新建工程配置表：一个项目只允许一套配置真源。
 *
 * <p><b>必填与选填的分界</b>：只有「缺了就一定跑不起来」的才是必填（仓库路径、解释器、
 * inventory、run_env）。仓库地址与分支是「克隆期」必填，本地已有工作区时可不填——
 * 所以它单独归入 {@link #REQUIRED_FOR_CLONE}，不混进执行前必填。
 */
public final class PytestProjectSettings {

    public static final String PREFIX = "pytest.";

    /** git 仓库地址（克隆期必填）。 */
    public static final String REPO_URL = PREFIX + "repo_url";
    /** 执行机上的本地工作区路径（执行前必填）。 */
    public static final String REPO_PATH = PREFIX + "repo_path";
    /** 分支 / tag，空则用仓库默认分支。 */
    public static final String BRANCH = PREFIX + "branch";
    /** 默认执行模式 fake / real（安全默认 fake，见 D5/P-2）。 */
    public static final String MODE_DEFAULT = PREFIX + "mode_default";
    /** pytest 解释器绝对路径（执行前必填；Python < 3.11 被拒）。 */
    public static final String PYTHON_BIN = PREFIX + "python_bin";
    /** run_env YAML 相对路径（执行前必填）。 */
    public static final String ENV_CONFIG = PREFIX + "env_config";
    /** inventory YAML 相对路径（执行前必填）。 */
    public static final String INVENTORY_PATH = PREFIX + "inventory_path";
    /** host_key 拼接规则：hostname / hostname@ip。 */
    public static final String INVENTORY_KEY_RULE = PREFIX + "inventory_key_rule";
    /** 追加参数（空格分隔，平台不做语义解析）。 */
    public static final String EXTRA_ARGS = PREFIX + "extra_args";
    /** run 级硬超时秒数，空则用全局 execution.test-timeout-seconds。 */
    public static final String TIMEOUT_S = PREFIX + "timeout_s";

    /** 执行前必填：缺任一项，预检直接拒绝（不进入执行队列）。 */
    public static final java.util.List<String> REQUIRED_FOR_EXECUTE = java.util.List.of(
            REPO_PATH, PYTHON_BIN, ENV_CONFIG, INVENTORY_PATH);

    /** 克隆期必填：本地无工作区时才需要。 */
    public static final java.util.List<String> REQUIRED_FOR_CLONE = java.util.List.of(REPO_URL);

    private PytestProjectSettings() {
    }

    /**
     * 读取一个项目的全部 pytest 配置（键去掉 {@code pytest.} 前缀）。
     * 缺失项不出现在 map 里，由调用方显式判「未配置」而不是拿空串当默认值。
     */
    public static Map<String, String> load(ProjectSettingRepository repo, long projectId) {
        return repo.findAll(projectId, PREFIX);
    }

    /** 取单个配置项的值（去空白；空串视同未配置）。 */
    public static String value(Map<String, String> settings, String key) {
        String raw = settings.get(key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /** 仓库根路径；未配置返回 null。 */
    public static Path repoPath(Map<String, String> settings) {
        String raw = value(settings, REPO_PATH);
        return raw == null ? null : Path.of(raw);
    }

    /**
     * 默认模式：未配置或非法值一律回落 {@code fake}。
     *
     * <p>这是 D5/P-2 的落地点：默认必须是 fake。框架侧 `run_env_default.yaml`
     * 现在写的是 `mode: real`（README 却称默认 Fake），平台侧不能跟着错。
     */
    public static String modeDefault(Map<String, String> settings) {
        String raw = value(settings, MODE_DEFAULT);
        if (raw == null) {
            return "fake";
        }
        String lower = raw.toLowerCase(java.util.Locale.ROOT);
        return "real".equals(lower) ? "real" : "fake";
    }

    /** 未配置的必填项清单（空列表 = 配置齐全）。 */
    public static java.util.List<String> missingForExecute(Map<String, String> settings) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String key : REQUIRED_FOR_EXECUTE) {
            if (value(settings, key) == null) {
                missing.add(shortName(key));
            }
        }
        return missing;
    }

    /** 配置键的短名（去掉 `pytest.`），用于界面与错误消息。 */
    public static String shortName(String key) {
        return key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key;
    }
}
