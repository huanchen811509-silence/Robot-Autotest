package com.qa.autotest.application.kernel.pytest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.DeviceRepository;
import com.qa.autotest.application.repository.NicModelDictRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.asset.DevicePort;
import com.qa.autotest.domain.asset.NicModelDict;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 网卡型号字典维护与资产映射体检（FR-v5.2-08 / 验收 B21–B23 / W52-03）。
 *
 * <p>三件事：<b>维护字典</b>（归一化后唯一）、<b>查映射</b>（给渲染器与预检用）、
 * <b>体检</b>（哪些资产型号还没归并）。第三件事是给运维看的——未映射不阻断执行，
 * 但会让「按型号比基线」这件事悄悄失效，所以必须能一眼数出来。
 */
@Service
public class NicModelDictService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final NicModelDictRepository dict;
    private final DeviceRepository devices;
    private final AuditService audit;

    public NicModelDictService(NicModelDictRepository dict, DeviceRepository devices,
                               AuditService audit) {
        this.dict = dict;
        this.devices = devices;
        this.audit = audit;
    }

    /** 字典行 + 归一化键 + 使用面。{@code key} 是算出来的，不落库。 */
    public record DictView(long id, String vendor, String model, String key,
                           String displayName, String formFactor, String vendorId,
                           String deviceId, String capabilitiesJson, boolean enabled,
                           long devicesUsing) {
    }

    /** 写入表单。{@code enabled} 可空（默认启用）。 */
    public record Command(String vendor, String model, String displayName, String formFactor,
                          String vendorId, String deviceId, String capabilitiesJson,
                          Boolean enabled) {
    }

    /** 资产侧型号覆盖：有多少端口填了 NIC 型号、其中多少能对上字典。 */
    public record Portfolio(long portsTotal,
                            long portsWithNic,
                            long mapped,
                            long unmapped,
                            double mappedPercent,
                            List<UnmappedNic> samples) {
    }

    public record UnmappedNic(String vendor, String model, long portCount) {
    }

    public List<DictView> list(boolean includeDisabled) {
        List<DictView> result = new ArrayList<>();
        for (NicModelDict row : dict.list(includeDisabled)) {
            result.add(new DictView(row.id(), row.vendor(), row.model(),
                    NicModelNormalizer.dictKey(row.vendor(), row.model()),
                    row.displayName(), row.formFactor(), row.vendorId(), row.deviceId(),
                    row.capabilitiesJson(), row.enabled(), dict.countDevicesUsing(row.id())));
        }
        return List.copyOf(result);
    }

    /**
     * 新建或更新（{@code id == null} 为新建）。
     *
     * <p>唯一性按**归一化键**判，不按原始文本：{@code ConnectX-7} 与 {@code connectx7}
     * 是同一张卡，允许共存等于允许两条互相矛盾的字典行。
     */
    public DictView save(Long id, Command command) {
        String vendor = NicModelNormalizer.display(command.vendor());
        String model = NicModelNormalizer.display(command.model());
        String key = NicModelNormalizer.dictKey(vendor, model);
        if (key == null) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "厂商与型号都必须填写（归一化后不能为空）");
        }
        String vendorId = normalizePciId(command.vendorId(), "vendor_id");
        String deviceId = normalizePciId(command.deviceId(), "device_id");
        String capabilities = normalizeJson(command.capabilitiesJson());
        String displayName = command.displayName() == null || command.displayName().isBlank()
                ? vendor + " " + model : command.displayName().trim();
        boolean enabled = command.enabled() == null || command.enabled();

        for (NicModelDict existing : dict.list(true)) {
            String existingKey = NicModelNormalizer.dictKey(existing.vendor(), existing.model());
            if (key.equals(existingKey) && (id == null || !existing.id().equals(id))) {
                throw new BusinessException(ErrorCode.CONFLICT,
                        "归一化后与已有行重复：" + existing.vendor() + " " + existing.model()
                                + "（同一张卡不许有两条字典，否则匹配结果取决于读取顺序）");
            }
        }

        NicModelDict row = new NicModelDict(id, vendor, model, displayName,
                NicModelNormalizer.display(command.formFactor()), vendorId, deviceId,
                capabilities, enabled);
        if (id == null) {
            long created = dict.insert(row, null);
            audit.record("nic-model:create", "nic_model_dict", String.valueOf(created),
                    "key=" + key);
            return list(true).stream().filter(v -> v.id() == created).findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                            "新建后读取失败：" + created));
        }
        dict.find(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "字典行不存在：" + id));
        dict.update(row, null);
        audit.recordChange("nic-model:update", "nic_model_dict", String.valueOf(id),
                "key=" + key, null);
        return list(true).stream().filter(v -> v.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "更新后读取失败：" + id));
    }

    /**
     * 启用 / 停用。
     *
     * <p>停用不检查引用数：停用是「以后不再匹配」，历史资产仍然指向它，
     * 拒绝停用会让运维无法纠正错登记的型号。但影响面要如实返回。
     */
    public DictView setEnabled(long id, boolean enabled) {
        dict.find(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                "字典行不存在：" + id));
        dict.setEnabled(id, enabled, null);
        audit.recordChange("nic-model:" + (enabled ? "enable" : "disable"),
                "nic_model_dict", String.valueOf(id),
                "devicesUsing=" + dict.countDevicesUsing(id), null);
        return list(true).stream().filter(v -> v.id() == id).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "状态变更后读取失败：" + id));
    }

    /** 按资产上的厂商/型号文本查字典行（只认启用行）。 */
    public Optional<NicModelDict> resolve(String vendor, String model) {
        String key = NicModelNormalizer.assetKey(vendor, model);
        if (key == null) {
            return Optional.empty();
        }
        for (NicModelDict row : dict.list(false)) {
            if (key.equals(NicModelNormalizer.dictKey(row.vendor(), row.model()))) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /**
     * 资产型号覆盖体检（项目视角）。
     *
     * <p>只统计填了 {@code nic_model} 的端口：没填的端口不是「未映射」，
     * 是「本来就没录」，混在一起会让未映射率永远降不下来，指标失去意义。
     */
    public Portfolio portfolio(long projectId) {
        Map<String, long[]> counter = new LinkedHashMap<>();
        long portsTotal = 0;
        long withNic = 0;
        long mapped = 0;
        for (DevicePort port : devices.portsOfVisibleDevices(projectId)) {
            portsTotal++;
            String model = port.nicModel();
            if (model == null || model.isBlank()) {
                continue;
            }
            withNic++;
            String vendor = port.nicVendor();
            if (resolve(vendor, model).isPresent()) {
                mapped++;
            } else {
                String key = (vendor == null ? "" : vendor.trim()) + "|" + model.trim();
                counter.computeIfAbsent(key, k -> new long[1])[0]++;
            }
        }
        long unmapped = withNic - mapped;
        double percent = withNic == 0 ? 0.0 : Math.round(mapped * 1000.0 / withNic) / 10.0;
        List<UnmappedNic> samples = new ArrayList<>();
        counter.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0])
                        .reversed())
                .limit(50)
                .forEach(e -> {
                    String[] parts = e.getKey().split("\\|", -1);
                    samples.add(new UnmappedNic(parts[0].isEmpty() ? null : parts[0],
                            parts[1], e.getValue()[0]));
                });
        return new Portfolio(portsTotal, withNic, mapped, unmapped, percent, List.copyOf(samples));
    }

    /** PCI ID 校验：4 位十六进制，可带 `0x` 前缀（录入习惯差异，入库统一去掉）。 */
    private static String normalizePciId(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("0x")) {
            value = value.substring(2);
        }
        if (!value.matches("[0-9a-f]{4}")) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    field + " 必须是 4 位十六进制（如 15b3 / 1021），当前为 " + raw);
        }
        return value;
    }

    /** 能力模板必须是 JSON 对象：数组或裸标量会让渲染器读不到键，静默退化成空能力。 */
    private static String normalizeJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                        "capabilities_json 必须是 JSON 对象，例如 {\"rdma\":true}");
            }
            return JSON.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "capabilities_json 不是合法 JSON：" + e.getOriginalMessage());
        }
    }
}
