package com.qa.autotest.application.kernel.pytest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从**已采集的主机清单**派生 GPU / CPU / 存储三类资产（v5.6.0 / FR-v5.6-41）。
 *
 * <p><b>为什么不新建 GPU/CPU/存储资产表</b>：这三类信息已经由
 * {@code HostInventoryCollector} 用只读命令采回并落在 {@code device_host_spec}，
 * 再拷贝一份进新表就等于出现第二个真源——采集更新后资产表不更新，渲染出来的
 * 就是上个月的机器配置，而且没有任何报错会提示这件事。派生只有一份来源：
 * 采集是事实，渲染是投影。
 *
 * <p><b>为什么返回空而不是编造</b>：没有任何一次采集时，{@code gpus}/{@code cpu}/
 * {@code storage} 就是空对象，同时由调用方写一条 warning 说明「未采集」。
 * 编一个 {@code unknown} 之类的占位项会诱使框架把「不知道」当成「有这块卡」。
 */
final class PytestHostAssets {

    static final PytestHostAssets EMPTY = new PytestHostAssets(Map.of(), Map.of(), Map.of(), false);

    private final Map<String, Object> gpus;
    private final Map<String, Object> cpus;
    private final Map<String, Object> storages;
    private final boolean collected;

    private PytestHostAssets(Map<String, Object> gpus, Map<String, Object> cpus,
                             Map<String, Object> storages, boolean collected) {
        this.gpus = gpus;
        this.cpus = cpus;
        this.storages = storages;
        this.collected = collected;
    }

    Map<String, Object> gpus() {
        return gpus;
    }

    Map<String, Object> cpus() {
        return cpus;
    }

    Map<String, Object> storages() {
        return storages;
    }

    /** 这三类里是否有任何一项可用（用于决定要不要写「未采集」警告）。 */
    boolean any() {
        return collected && !(gpus.isEmpty() && cpus.isEmpty() && storages.isEmpty());
    }

    /** 全部资源键（不带主机前缀），供 run_env 的资源池拼 {@code host/key}。 */
    List<String> gpuKeys() {
        return new ArrayList<>(gpus.keySet());
    }

    List<String> cpuKeys() {
        return new ArrayList<>(cpus.keySet());
    }

    List<String> storageKeys() {
        return new ArrayList<>(storages.keySet());
    }

    /**
     * 解析一次采集的 {@code spec_json}。
     *
     * <p>只认 {@code value} 非空且 {@code reason} 为空的项——与
     * {@code HostInventoryItem.collected()} 同一判据：采到一半的项（有 reason）
     * 与「真的没有这块卡」必须区分，前者不能当成「空资产」静默通过。
     *
     * @param specJson {@code HostInventory} 的序列化结果；null/空/坏 JSON 一律返回 {@link #EMPTY}
     */
    static PytestHostAssets from(String specJson, ObjectMapper mapper) {
        if (specJson == null || specJson.isBlank() || mapper == null) {
            return EMPTY;
        }
        JsonNode root;
        try {
            root = mapper.readTree(specJson);
        } catch (Exception ex) {
            return EMPTY;
        }
        JsonNode items = root.path("items");
        if (!items.isArray()) {
            return EMPTY;
        }
        Map<String, Object> gpus = new LinkedHashMap<>();
        Map<String, Object> cpus = new LinkedHashMap<>();
        Map<String, Object> storages = new LinkedHashMap<>();
        for (JsonNode item : items) {
            String category = item.path("category").asText("");
            String reason = item.path("reason").asText("");
            JsonNode value = item.path("value");
            if (value.isMissingNode() || value.isNull() || !reason.isBlank()) {
                continue;
            }
            switch (category) {
                case "GPU" -> gpus.putAll(gpus(value));
                case "CPU" -> {
                    if (value.isObject() && !value.isEmpty()) {
                        cpus.put("cpu0", mapper.convertValue(value, Map.class));
                    }
                }
                case "DISK" -> storages.putAll(storages(value, mapper));
                default -> {
                    // MEMORY / NIC / IP / ROCE 等不在这三类的映射范围内
                }
            }
        }
        // 不能再用 Map.copyOf：它返回的是无序不可变 Map，同一台机器两次渲染的
        // gpu0/gpu1、盘序可能不同，产物 sha 随之变化，证据就无法「同配置同哈希」比对。
        return new PytestHostAssets(java.util.Collections.unmodifiableMap(gpus),
                java.util.Collections.unmodifiableMap(cpus),
                java.util.Collections.unmodifiableMap(storages), true);
    }

    /** GPU 列表 → {@code gpu0/gpu1/...}；字段沿用采集器口径，不另起一套名字。 */
    private static Map<String, Object> gpus(JsonNode value) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!value.isArray()) {
            return result;
        }
        int index = 0;
        for (JsonNode card : value) {
            if (!card.isObject()) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("index", index);
            one.put("name", card.path("name").asText(""));
            one.put("memory_total", card.path("memoryTotal").asText(""));
            one.put("driver_version", card.path("driverVersion").asText(""));
            result.put("gpu" + index, one);
            index++;
        }
        return result;
    }

    /** 磁盘 → 每个物理盘一项，键取盘名（{@code /dev/nvme0n1} → {@code nvme0n1}）。 */
    private static Map<String, Object> storages(JsonNode value, ObjectMapper mapper) {
        Map<String, Object> result = new LinkedHashMap<>();
        JsonNode disks = value.path("disks");
        if (disks.isArray()) {
            for (JsonNode disk : disks) {
                String name = disk.path("name").asText("");
                String key = sanitize(name);
                if (key.isEmpty()) {
                    continue;
                }
                // loop* 是 snap 的只读回环挂载（实测一台机器 19 个、几十 MB），
                // 既不是可写存储、也不是性能对象；放进资源池只会让「有用例挑了 loop7」
                // 这种误选出现，而 rootfs / nvme / sda 才是真正能承载 IO 的盘。
                if (key.startsWith("loop")) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("device", name);
                one.put("size", disk.path("size").asText(""));
                one.put("type", disk.path("type").isNull()
                        ? "" : disk.path("type").asText(""));
                result.put(key, one);
            }
        }
        JsonNode root = value.path("root");
        if (root.isObject() && !root.isEmpty()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("device", "rootfs");
            one.put("size", root.path("size").asText(""));
            one.put("used", root.path("used").asText(""));
            one.put("available", root.path("available").asText(""));
            one.put("mount", "/");
            // rootfs 与物理盘是不同粒度的事实：物理盘可能没有，rootfs 一定有（能跑命令就说明有根）。
            // 键前缀区分，避免与名为 root 的盘撞名。
            result.put("rootfs", one);
        }
        return result;
    }

    /** 盘名里出现 {@code /} 会让资源键变成 {@code host//dev/x}，必须去掉非安全字符。 */
    private static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : raw.trim().toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '-' || ch == '_' || ch == '.') {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
