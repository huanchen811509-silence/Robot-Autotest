package com.qa.autotest.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.ops.AlertNotifier;
import com.qa.autotest.domain.ops.AlertChannel;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Webhook 告警投递（FR-3.3-09）。
 *
 * <p>只支持 http/https；带密钥时用 HMAC-SHA256 放 {@code X-Alert-Signature}。
 * 邮件通道没有 SMTP 配置时返回 DEGRADED 并写明原因，事件本身保留，
 * 不会因为「发不出去」就把告警吞掉。
 */
@Component
public class HttpAlertNotifier implements AlertNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(HttpAlertNotifier.class);

    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public HttpAlertNotifier(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Result send(AlertChannel channel, String secret, Map<String, Object> payload) {
        if ("EMAIL".equalsIgnoreCase(channel.kind())) {
            return new Result("DEGRADED", "邮件通道未配置 SMTP，已记录事件但未投递");
        }
        if (!"WEBHOOK".equalsIgnoreCase(channel.kind())) {
            return new Result("DEGRADED", "未知通道类型 " + channel.kind());
        }
        String target = channel.target();
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) {
            return new Result("FAILED", "Webhook 地址必须以 http(s):// 开头");
        }
        try {
            String body = mapper.writeValueAsString(payload);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Alert-Event", String.valueOf(payload.get("eventType")))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (secret != null && !secret.isEmpty()) {
                builder.header("X-Alert-Signature", hmac(secret, body));
            }
            HttpResponse<String> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new Result("SENT", null);
            }
            return new Result("FAILED", "Webhook 返回 HTTP " + response.statusCode());
        } catch (Exception failure) {
            LOG.warn("告警投递失败 target={}", target, failure);
            return new Result("FAILED", "投递异常：" + failure.getClass().getSimpleName());
        }
    }

    static String hmac(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) {
            hex.append(String.format("%02x", value));
        }
        return hex.toString();
    }
}
