package com.qa.autotest.application.env;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.repository.DeviceSnapshotRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.env.DeviceSnapshot;
import com.qa.autotest.domain.env.DeviceSnapshotItem;
import com.qa.autotest.domain.host.HostInventoryRecord;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HexFormat;
import org.springframework.stereotype.Service;

/**
 * 设备快照服务（v5.15.0 / FR-v5.15-01~03）。
 *
 * <p>把一次主机清单采集的 {@code spec_json} **按项展平**成可查询事实；内容寻址 hash
 * 由「按项规范化文本」算出，因此同一份事实重复采集只会复用同一份快照，
 * 不会把历史时点越攒越多，也不会覆盖既有项。
 */
@Service
public class DeviceSnapshotService {

    public static final String SOURCE_HOST_INVENTORY = "HOST_INVENTORY";

    /** 按项草稿（服务内部装配结果，不落库）。 */
    public record Draft(String itemKey, String category, String command, String valueJson,
                        String valueHash, boolean collected, String reason) {
    }

    /** 按项对比的一行；change 只有 added / removed / changed 三态。 */
    public record ItemDiff(String key, String change, String before, String after) {
    }

    private final DeviceSnapshotRepository repository;
    private final ObjectMapper mapper;

    public DeviceSnapshotService(DeviceSnapshotRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    /** 采集双写入口：主清单已落库后才调用，返回快照 id。 */
    public long capture(long projectId, HostInventoryRecord record) {
        if (record == null || record.specJson() == null || record.specJson().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "快照来源清单为空");
        }
        List<Draft> drafts;
        try {
            drafts = flatten(mapper.readTree(record.specJson()));
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT, "清单不是合法 JSON：" + ex.getMessage());
        }
        String hash = hashOf(drafts);
        Instant collectedAt = record.collectedAt() == null ? Instant.now() : record.collectedAt();
        DeviceSnapshot header = new DeviceSnapshot(0L, projectId, record.deviceId(), record.host(),
                SOURCE_HOST_INVENTORY, hash, record.specHash(), record.id(), collectedAt,
                drafts.size(), null);
        long snapshotId = repository.upsertSnapshot(header, null);
        List<DeviceSnapshotItem> rows = new ArrayList<>();
        for (Draft draft : drafts) {
            rows.add(new DeviceSnapshotItem(0L, projectId, snapshotId, draft.itemKey(),
                    draft.category(), draft.command(), draft.valueJson(), draft.valueHash(),
                    draft.collected(), draft.reason(), null));
        }
        repository.insertItems(projectId, snapshotId, rows);
        return snapshotId;
    }

    public DeviceSnapshot snapshot(long projectId, long id) {
        return repository.find(projectId, id).orElseThrow(
                () -> new BusinessException(ErrorCode.NOT_FOUND, "设备快照不存在: " + id));
    }

    public List<DeviceSnapshotItem> items(long projectId, long id, String key) {
        snapshot(projectId, id);
        return repository.items(projectId, id, key);
    }

    public List<DeviceSnapshotItem> latestItems(long projectId, String key) {
        return repository.latestItems(projectId, key);
    }

    public List<DeviceSnapshot> list(long projectId, int limit) {
        return repository.list(projectId, limit);
    }

    /** 按项对比两份快照：读的是按项事实，不是把两份 LONGTEXT 做文本 diff。 */
    public List<ItemDiff> diffByItem(long projectId, long beforeId, long afterId) {
        Map<String, DeviceSnapshotItem> left = byKey(repository.items(projectId, beforeId, null));
        Map<String, DeviceSnapshotItem> right = byKey(repository.items(projectId, afterId, null));
        if (left.isEmpty() && right.isEmpty()) {
            if (repository.find(projectId, beforeId).isEmpty()
                    || repository.find(projectId, afterId).isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "对比的快照不存在");
            }
        }
        List<ItemDiff> result = new ArrayList<>();
        for (Map.Entry<String, DeviceSnapshotItem> entry : right.entrySet()) {
            DeviceSnapshotItem before = left.get(entry.getKey());
            String after = display(entry.getValue());
            if (before == null) {
                result.add(new ItemDiff(entry.getKey(), "added", null, after));
            } else if (!display(before).equals(after)) {
                result.add(new ItemDiff(entry.getKey(), "changed", display(before), after));
            }
        }
        for (Map.Entry<String, DeviceSnapshotItem> entry : left.entrySet()) {
            if (!right.containsKey(entry.getKey())) {
                result.add(new ItemDiff(entry.getKey(), "removed", display(entry.getValue()), null));
            }
        }
        return result;
    }

    private static Map<String, DeviceSnapshotItem> byKey(List<DeviceSnapshotItem> rows) {
        Map<String, DeviceSnapshotItem> result = new LinkedHashMap<>();
        for (DeviceSnapshotItem row : rows) {
            result.put(row.itemKey(), row);
        }
        return result;
    }

    /** 对比展示值：未采到项显示原因，不把 null 伪装成空串。 */
    private static String display(DeviceSnapshotItem item) {
        if (item == null) {
            return "";
        }
        return item.collected() ? String.valueOf(item.valueJson()) : "未采集到：" + item.reason();
    }

    /** 展平：每类一项，重复类追加序号；未采到项 valueJson=null + reason 非空。 */
    static List<Draft> flatten(JsonNode root) {
        List<Draft> drafts = new ArrayList<>();
        JsonNode items = root == null ? null : root.path("items");
        if (items == null || !items.isArray()) {
            return drafts;
        }
        Set<String> used = new LinkedHashSet<>();
        for (JsonNode item : items) {
            String category = item.path("category").asText("");
            String key = category;
            int seq = 2;
            while (used.contains(key)) {
                key = category + "#" + seq++;
            }
            used.add(key);
            JsonNode value = item.get("value");
            String reason = item.path("reason").asText(null);
            boolean collected = value != null && !value.isNull()
                    && (reason == null || reason.isBlank());
            String valueJson = collected ? value.toString() : null;
            String command = item.path("command").asText(null);
            drafts.add(new Draft(key, category, command, valueJson,
                    valueJson == null ? null : sha256(valueJson), collected,
                    collected ? null : reason == null || reason.isBlank()
                            ? "未采集到（未给出原因）" : reason));
        }
        drafts.sort(Comparator.comparing(Draft::itemKey));
        return drafts;
    }

    /** 内容寻址：按 itemKey 排序后拼接，避免数组顺序抖动产生假的新快照。 */
    static String hashOf(List<Draft> drafts) {
        StringBuilder sb = new StringBuilder();
        for (Draft draft : drafts) {
            sb.append(draft.itemKey()).append('|').append(draft.category()).append('|')
                    .append(draft.command()).append('|').append(draft.valueJson()).append('|')
                    .append(draft.collected()).append('|').append(draft.reason()).append('\n');
        }
        return sha256(sb.toString());
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 缺少 SHA-256", impossible);
        }
    }
}
