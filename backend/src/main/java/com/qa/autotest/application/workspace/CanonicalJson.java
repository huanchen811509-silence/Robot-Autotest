package com.qa.autotest.application.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 清单规范形 JSON（契约 §3）：键名字典序、无缩进、UTF-8。
 *
 * <p>必须与 {@code tools/check_workspace_bundle.py} 的
 * {@code json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False)} 逐字节一致；
 * 两边任何一边改格式，HMAC 都会对不上——这正是「签名覆盖规范形而不是覆盖文件字节」的代价与保障。</p>
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {
    }

    public static String write(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        writeNode(node, sb);
        return sb.toString();
    }

    private static void writeNode(JsonNode node, StringBuilder sb) {
        if (node == null || node.isNull()) {
            sb.append("null");
            return;
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            sb.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(quote(names.get(i))).append(':');
                writeNode(node.get(names.get(i)), sb);
            }
            sb.append('}');
            return;
        }
        if (node.isArray()) {
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                writeNode(node.get(i), sb);
            }
            sb.append(']');
            return;
        }
        if (node.isTextual()) {
            sb.append(quote(node.textValue()));
            return;
        }
        if (node.isBoolean()) {
            sb.append(node.booleanValue());
            return;
        }
        if (node.isIntegralNumber()) {
            sb.append(node.bigIntegerValue().toString());
            return;
        }
        if (node.isNumber()) {
            sb.append(node.decimalValue().toPlainString());
            return;
        }
        sb.append(quote(node.asText()));
    }

    private static String quote(String text) {
        try {
            return MAPPER.writeValueAsString(text);
        } catch (JsonProcessingException exc) {
            throw new IllegalArgumentException("规范形 JSON 字符串编码失败", exc);
        }
    }
}
