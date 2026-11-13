package com.qa.autotest.application.kernel.pytest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * GPU 型号规格表目录（v5.6.0 / FR-v5.6-41）。
 *
 * <p><b>为什么需要它</b>：用例库按 {@code device_specs.yaml} 的键取规格
 * （{@code get_gpu_spec(gpu.config["model"])}），并按 {@code vendor} 选 GPU 适配器
 * （{@code GpuAdapterRegistry.resolve_config}）。采集器只给型号全名
 * （{@code NVIDIA RTX PRO 6000 Blackwell Server Edition}），两者之间没有算法关系——
 * 键是 {@code pro-6000} 这种人工起的短名，无法从全名推导。平台渲染 inventory 时
 * 若只写 {@code name}，用例先报 {@code KeyError: 未登记 gpus 型号 None}；
 * 补上 model 但漏了 vendor，又会在取适配器时报
 * {@code NotImplementedError: 厂商尚未适配}——两次都被指向「用例库缺配置」，
 * 而真实原因都在「inventory 少字段」。
 *
 * <p><b>为什么读用例库的规格文件而不是平台自己存一份</b>：规格表是框架侧的唯一真源，
 * 平台再存一份就等于两份会漂移的清单。这里只做只读映射：用 {@code display_name}
 * 反查键与厂商，不复制判据数值、不解释语义。
 *
 * <p><b>为什么匹配不到就留空 + 告警</b>：猜一个近似键（比如把全名拍平成
 * {@code nvidia-rtx-pro-6000-...}）会让用例拿着错的规格继续跑，得出「GDS 不支持」
 * 这种看似真实的结论。留空 + 一条可执行的告警，让人去补登记，比假装成功好。
 */
final class PytestGpuModelCatalog {

    static final PytestGpuModelCatalog EMPTY = new PytestGpuModelCatalog(Map.of());

    private static final String SPEC_RELATIVE = "config/specs/device_specs.yaml";

    /** 规格表里的一条 GPU 型号：规格键 + 厂商。 */
    record Entry(String model, String vendor) {

        boolean complete() {
            return !model.isEmpty() && !vendor.isEmpty();
        }
    }

    /** 归一化后的 display_name / 键 → 型号条目。 */
    private final Map<String, Entry> entries;

    private PytestGpuModelCatalog(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /**
     * 从用例库读 {@code config/specs/device_specs.yaml} 的 {@code gpus} 段。
     *
     * @param repoPath 用例库仓库根；为空或文件缺失时返回 {@link #EMPTY} 并写告警
     */
    @SuppressWarnings("unchecked")
    static PytestGpuModelCatalog load(Path repoPath, List<String> warnings) {
        if (repoPath == null) {
            warnings.add("未配置 pytest.repo_path，无法把 GPU 型号映射成规格键（inventory 的 model/vendor 会留空）");
            return EMPTY;
        }
        Path file = repoPath.resolve(SPEC_RELATIVE);
        if (!Files.isReadable(file)) {
            warnings.add("未找到用例库规格文件 " + file
                    + "，GPU 型号无法映射成规格键（inventory 的 model/vendor 会留空）");
            return EMPTY;
        }
        Map<String, Object> root;
        try {
            Object loaded = new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
            if (!(loaded instanceof Map)) {
                warnings.add("用例库规格文件结构异常（顶层不是映射）：" + file);
                return EMPTY;
            }
            root = (Map<String, Object>) loaded;
        } catch (IOException | RuntimeException ex) {
            warnings.add("读取用例库规格文件失败：" + file + " — " + ex.getMessage());
            return EMPTY;
        }
        Object gpus = root.get("gpus");
        if (!(gpus instanceof Map<?, ?> table)) {
            warnings.add("用例库规格文件缺少 gpus 段：" + file);
            return EMPTY;
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Map.Entry<?, ?> row : table.entrySet()) {
            String specKey = String.valueOf(row.getKey());
            String vendor = "";
            String display = specKey;
            if (row.getValue() instanceof Map<?, ?> spec) {
                Object v = spec.get("vendor");
                if (v != null) {
                    vendor = String.valueOf(v);
                }
                Object d = spec.get("display_name");
                if (d != null) {
                    display = String.valueOf(d);
                }
            }
            Entry entry = new Entry(specKey, vendor);
            entries.putIfAbsent(normalize(specKey), entry);
            entries.putIfAbsent(normalize(display), entry);
        }
        return new PytestGpuModelCatalog(Map.copyOf(entries));
    }

    /** 按采集到的型号全名取型号条目；取不到返回空条目（调用方负责告警）。 */
    Entry lookup(String gpuName) {
        return entries.getOrDefault(normalize(gpuName), new Entry("", ""));
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    /** 与框架 {@code registry.normalize} 同一口径：小写、下划线转连字符、压缩空白。 */
    static String normalize(String value) {
        if (value == null) {
            return "*";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replaceAll("\\s+", "-");
    }
}
