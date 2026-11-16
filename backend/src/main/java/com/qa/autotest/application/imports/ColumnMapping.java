package com.qa.autotest.application.imports;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 导入字段映射（v5.18.0 / FR-v5.18-01）。
 *
 * <p>映射的含义：把「工作簿里的原始列名」指派到「系统内部字段名」，只在用户于映射页
 * 显式改列时生效。空映射 = 沿用表头自动识别，**语义与 5.17 完全一致**（判据 5）。
 *
 * <p>为什么键要归一化：同一份台账在不同人手里，「设备 名称」「设备名称」「device name」
 * 都会出现；如果按原样比对，用户在映射页看到的是 A 列，后端认的却是 B 列，改列就等于白改。
 */
public final class ColumnMapping {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ColumnMapping() {
    }

    /**
     * 解析 JSON 形态的映射：{@code {"源表头": "目标字段"}}。
     *
     * <p>空串 / null → 空映射（老行为）。非法 JSON → 400，**不静默吞掉**：
     * 用户以为改了列，实际按旧表头导入，是最难查的一类错。
     */
    public static Map<String, String> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, String> raw;
        try {
            raw = MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    "字段映射不是合法的 JSON 对象（形如 {\"源列名\": \"目标字段\"}）：" + ex.getMessage());
        }
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
                out.put(normalize(key), value.trim());
            }
        });
        return out;
    }

    /** 归一化查表；未命中返回 null（表示该列沿用自动识别）。 */
    public static String lookup(Map<String, String> mapping, String header) {
        if (mapping == null || mapping.isEmpty() || header == null) {
            return null;
        }
        return mapping.get(normalize(header));
    }

    public static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
