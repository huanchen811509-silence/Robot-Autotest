package com.qa.autotest.application.execution;

import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 运行提交幂等键解析（FR-3.2-05 / D32-4）。
 *
 * <p>优先级：{@code Idempotency-Key} 请求头 > 请求体 {@code runRequestId} > 请求指纹。
 * 指纹按「端点 + 项目 + 全部业务参数 + 时间窗」做 SHA-256，取 64 位十六进制，正好等于
 * {@code run.run_request_id} 的列宽，因此数据库唯一键 {@code uk_run_project_request} 直接兜底。
 *
 * <p>指纹带时间窗（默认 120 秒）而不是永久唯一：双击 / 网络重试落在同一窗口内只产生一条运行，
 * 过了窗口的相同参数则视为「用户有意重跑」，生成新的键，避免同配置永远无法再次执行。
 *
 * <p>开关关闭时无 Header / body key 返回 {@code null}，由 {@link RunService} 生成一次性 UUID，
 * 等价回退到 v3.1 行为。
 */
public final class RunIdempotency {

    /** 与 {@code run.run_request_id VARCHAR(64)} 一致。 */
    public static final int MAX_KEY_LENGTH = 64;

    private static final Pattern ALLOWED_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    private RunIdempotency() {
    }

    /**
     * 解析本次提交使用的幂等键。
     *
     * @param headerKey           {@code Idempotency-Key} 请求头，可为 null
     * @param bodyKey             请求体里的 {@code runRequestId}，可为 null
     * @param endpoint            端点标识（不同入口的同参数请求不互相去重）
     * @param now                 当前时间；指纹按 {@code windowSeconds} 分桶
     * @param fingerprintEnabled  关闭时无显式键则返回 null
     * @param windowSeconds       指纹去重窗口，{@code <=0} 按 1 秒
     * @param material            参与指纹的业务参数，按传入顺序拼接
     * @return 1~64 位幂等键；{@code null} 表示不做幂等，由服务端生成随机请求号
     */
    public static String resolve(String headerKey, String bodyKey, String endpoint, Instant now,
                                 boolean fingerprintEnabled, int windowSeconds,
                                 Object... material) {
        String header = trimToNull(headerKey);
        if (header != null) {
            return requireValid(header, "Idempotency-Key");
        }
        String body = trimToNull(bodyKey);
        if (body != null) {
            return requireValid(body, "runRequestId");
        }
        if (!fingerprintEnabled) {
            return null;
        }
        long window = Math.max(1, windowSeconds);
        long bucket = Math.max(0, now.getEpochSecond()) / window;
        String canonical = endpoint + "|" + canonical(material) + "|" + bucket;
        return sha256(canonical);
    }

    /**
     * 请求指纹的稳定序列化：null 记为 {@code -}，列表按原顺序逗号连接，字符串去首尾空白。
     * 不做排序，因为套件顺序、设备顺序都是调用方的显式输入。
     */
    static String canonical(Object... material) {
        StringBuilder builder = new StringBuilder();
        for (Object item : material) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(token(item));
        }
        return builder.toString();
    }

    private static String token(Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof List<?> list) {
            StringBuilder nested = new StringBuilder();
            for (Object element : list) {
                if (nested.length() > 0) {
                    nested.append(',');
                }
                nested.append(token(element));
            }
            return nested.toString();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? "-" : text;
    }

    private static String requireValid(String key, String label) {
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    label + " 超过 " + MAX_KEY_LENGTH + " 字符");
        }
        if (!ALLOWED_KEY.matcher(key).matches()) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT,
                    label + " 只允许字母、数字与 . _ : -，长度 1~" + MAX_KEY_LENGTH);
        }
        return key;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sha256(String material) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
