package com.qa.autotest.application.kernel.pytest;

import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ProjectSettingRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * pytest 工程配置的读写与校验（FR-v5.2-02 / 验收 B4–B6）。
 *
 * <p><b>为什么不能让页面直接 PUT 整张 map</b>：配置项是执行命令的输入，
 * 一个拼错的键（`python-bin` vs `python_bin`）会被静默存进 KV 表、执行时读不到、
 * 表现为「配了但还是报未配置」。所以这里做三件事：未知键拒绝、逐项格式校验、
 * 保存后立刻回一次健康检查把结果摆在页面上。
 *
 * <p><b>原子性</b>：任何一项校验失败即整体拒绝，不做「存一半」。半保存的配置比
 * 保存失败更难排查——页面显示保存成功，实际只有一半字段生效。
 */
@Service
public class PytestSettingsService {

    /** 允许写的键（白名单，防止未知键静默沉淀）。 */
    private static final Set<String> KNOWN = Set.of(
            shortName(PytestProjectSettings.REPO_URL),
            shortName(PytestProjectSettings.REPO_PATH),
            shortName(PytestProjectSettings.BRANCH),
            shortName(PytestProjectSettings.MODE_DEFAULT),
            shortName(PytestProjectSettings.PYTHON_BIN),
            shortName(PytestProjectSettings.ENV_CONFIG),
            shortName(PytestProjectSettings.INVENTORY_PATH),
            shortName(PytestProjectSettings.INVENTORY_KEY_RULE),
            shortName(PytestProjectSettings.EXTRA_ARGS),
            shortName(PytestProjectSettings.TIMEOUT_S));

    /** 超时下限：低于 30 秒连框架的 setup 都跑不完，配了等于必然超时。 */
    private static final int TIMEOUT_MIN = 30;
    /** 超时上限：24 小时。超过它不是「宽容」而是「卡死也发现不了」。 */
    private static final int TIMEOUT_MAX = 86_400;

    private final ProjectSettingRepository settings;
    private final AuditService audit;
    private final PytestHealthService health;

    public PytestSettingsService(ProjectSettingRepository settings, AuditService audit,
                                 PytestHealthService health) {
        this.settings = settings;
        this.audit = audit;
        this.health = health;
    }

    private static String shortName(String key) {
        return PytestProjectSettings.shortName(key);
    }

    /** 单项校验失败。{@code field} 用短名（页面上的字段名），不带 `pytest.` 前缀。 */
    public record FieldError(String field, String message) {
    }

    /**
     * 保存结果。{@code health} 是保存后立刻探到的状态——页面不必再单独请求一次，
     * 也不会出现「保存成功但页面还显示旧健康状态」。
     */
    public record SaveResult(Map<String, String> settings,
                             List<String> missingForExecute,
                             List<FieldError> errors,
                             PytestHealthService.Health health) {

        public boolean saved() {
            return errors.isEmpty();
        }
    }

    /**
     * 覆盖写：只处理请求里出现的键，未出现的保持原值；值为空串表示「恢复未配置」。
     *
     * @throws BusinessException 任一项非法时抛 {@code INVALID_ARGUMENT}，
     *                           {@code detail} 里带字段名，页面可高亮到具体输入框
     */
    public SaveResult save(long projectId, Map<String, String> body) {
        Map<String, String> incoming = normalize(body);
        List<FieldError> errors = validate(incoming);
        if (!errors.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    describe(errors));
        }
        Map<String, String> before = settings.findAll(projectId, PytestProjectSettings.PREFIX);
        boolean pythonChanged = false;
        for (Map.Entry<String, String> entry : incoming.entrySet()) {
            String full = PytestProjectSettings.PREFIX + entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                settings.remove(projectId, full);
            } else {
                settings.put(projectId, full, value, null);
            }
            if (PytestProjectSettings.PYTHON_BIN.equals(full)) {
                pythonChanged = true;
            }
        }
        Map<String, String> after = settings.findAll(projectId, PytestProjectSettings.PREFIX);
        // 审计只记「哪些键变了」，不记值：repo_path 里可能含用户名，extra_args 可能含
        // 环境相关的绝对路径，都不是该进审计正文的东西。
        audit.recordChange("pytest-setting:update", "project_setting",
                String.valueOf(projectId), changedKeys(before, after), null);

        // 解释器换了必须重探（缓存按 pythonBin 分键，理论上不会命中旧的，但显式刷更稳）。
        PytestHealthService.Health snapshot = health.health(projectId, pythonChanged);
        return new SaveResult(after,
                PytestProjectSettings.missingForExecute(after), List.of(), snapshot);
    }

    /**
     * 只校验不落库，供页面「保存前试算」与预检复用。
     *
     * @return 空列表表示配置可用（不代表必填齐全）
     */
    public List<FieldError> check(Map<String, String> body) {
        return validate(normalize(body));
    }

    /** 把请求体规整成「短名 → 值」，同时拒绝未知键。 */
    private Map<String, String> normalize(Map<String, String> body) {
        Map<String, String> result = new LinkedHashMap<>();
        if (body == null) {
            return result;
        }
        List<FieldError> unknown = new ArrayList<>();
        for (Map.Entry<String, String> entry : body.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty()) {
                continue;
            }
            String name = key.startsWith(PytestProjectSettings.PREFIX)
                    ? key.substring(PytestProjectSettings.PREFIX.length()) : key;
            if (!KNOWN.contains(name)) {
                unknown.add(new FieldError(name, "不是已知的 pytest 配置项"));
                continue;
            }
            String value = entry.getValue() == null ? null : entry.getValue().trim();
            result.put(name, value == null || value.isEmpty() ? null : value);
        }
        if (!unknown.isEmpty()) {
            // 未知键单独抛：它说明调用方对着旧版字段名写的，改值也没意义
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, describe(unknown));
        }
        return result;
    }

    /** 逐项格式校验。只校验「出现的项」，未出现的（= 不修改）不判。 */
    private List<FieldError> validate(Map<String, String> values) {
        List<FieldError> errors = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                continue;
            }
            switch (name) {
                case "mode_default" -> {
                    String lower = value.toLowerCase(java.util.Locale.ROOT);
                    if (!lower.equals("fake") && !lower.equals("real")) {
                        errors.add(new FieldError(name, "只能是 fake 或 real（安全默认 fake）"));
                    }
                }
                case "timeout_s" -> {
                    Integer seconds = parsePositive(name, value, errors);
                    if (seconds != null && (seconds < TIMEOUT_MIN || seconds > TIMEOUT_MAX)) {
                        errors.add(new FieldError(name, "取值范围 " + TIMEOUT_MIN + "~"
                                + TIMEOUT_MAX + " 秒，当前 " + seconds));
                    }
                }
                case "repo_path", "python_bin" -> {
                    if (!Path.of(value).isAbsolute()) {
                        // 相对路径会按后端进程的 CWD 解析，而 CWD 是部署目录不是工程目录，
                        // 结果必然指向一个不存在的位置，且报错信息完全指不到配置项。
                        errors.add(new FieldError(name, "必须是绝对路径，当前为 " + value));
                    }
                }
                case "env_config", "inventory_path" -> {
                    if (Path.of(value).isAbsolute()) {
                        errors.add(new FieldError(name, "必须是相对仓库根的路径"
                                + "（绝对路径换台机器就失效），当前为 " + value));
                    }
                }
                case "inventory_key_rule" -> {
                    if (!value.equals("hostname") && !value.equals("hostname@ip")) {
                        errors.add(new FieldError(name, "只能是 hostname 或 hostname@ip"));
                    }
                }
                case "extra_args" -> {
                    if (value.contains("--env_config") || value.contains("--mode")
                            || value.contains("--junitxml")) {
                        // 这三项平台自己拼，用户再写一遍会得到两个值，pytest 取最后一个，
                        // 表现为「平台配的没生效」，排查成本很高。
                        errors.add(new FieldError(name, "不要在这里重复 --env_config / --mode "
                                + "/ --junitxml，这三项由平台装配"));
                    }
                    if (value.contains("&&") || value.contains(";") || value.contains("|")) {
                        errors.add(new FieldError(name, "不允许 shell 连接符（参数按空格切分后"
                                + "逐个传参，不经 shell）"));
                    }
                }
                default -> {
                    // branch / repo_url：自由文本，不做格式限制（schema 因远端而异）
                }
            }
        }
        return errors;
    }

    private static Integer parsePositive(String field, String value, List<FieldError> errors) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                errors.add(new FieldError(field, "必须是正整数"));
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            errors.add(new FieldError(field, "必须是整数秒数，当前为 " + value));
            return null;
        }
    }

    private static String describe(List<FieldError> errors) {
        List<String> parts = new ArrayList<>();
        for (FieldError error : errors) {
            parts.add(error.field() + ": " + error.message());
        }
        return String.join("；", parts);
    }

    /** 变更键名（不含值），审计用。顺序按字典序，便于比对。 */
    private static String changedKeys(Map<String, String> before, Map<String, String> after) {
        List<String> changed = new ArrayList<>();
        java.util.TreeSet<String> all = new java.util.TreeSet<>();
        all.addAll(before.keySet());
        all.addAll(after.keySet());
        for (String key : all) {
            String oldValue = before.get(key);
            String newValue = after.get(key);
            if (oldValue == null ? newValue != null : !oldValue.equals(newValue)) {
                changed.add(key);
            }
        }
        return changed.isEmpty() ? "changed=none" : "changed=" + String.join(",", changed);
    }
}
