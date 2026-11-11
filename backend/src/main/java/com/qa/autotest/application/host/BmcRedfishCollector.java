package com.qa.autotest.application.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.domain.host.HostInventory;
import com.qa.autotest.domain.host.HostInventoryItem;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.springframework.stereotype.Component;

/**
 * BMC / 带外采集器（v4.12.0 / FR-v4.12-08）。
 *
 * <p><b>为什么走 Redfish 而不是 SSH</b>：现场实测两家的 SSH 都不是 Linux ——
 * 浪潮 BMC（`10.202.54.11`）登录后落到厂商 CLI（{@code bmclog/chassis/mc/diagnose}），
 * 没有 {@code uname}；Dell R750 的 BMC 直接 {@code Permission denied}。
 * 但两家都开了 Redfish（{@code /redfish/v1} 返回 200，浪潮 v1_5_2 / Dell v1_17_0），
 * 所以带外通道统一用 Redfish 只读 GET。
 *
 * <p><b>只读且只取标准字段</b>：不碰 OEM 扩展，不 POST 任务、不开关机。
 * 端点版本差异（v1_5_2 vs v1_17_0）通过「缺字段置 null + PARTIAL」吸收，
 * 不做版本分支——两份版本分支必然只有一份被真机跑过。
 *
 * <p><b>证书与主机名都不校验，且只影响本通道</b>：BMC 一律自签，而且现场证书的
 * SAN 装的是厂商内部地址（实测浪潮 NF5468M7：{@code SAN=DNS:megarac.com, IP:169.254.0.17}），
 * 拿管理网 IP `10.202.54.11` 去连必然对不上，于是要放开两层：
 * <ul>
 *   <li>信任链：{@link #trustManager()} 全放行；</li>
 *   <li>主机名：**必须用 {@link X509ExtendedTrustManager} 而不是 {@link
 *       javax.net.ssl.X509TrustManager}**。JSSE 会拿普通 X509TrustManager 去套一层
 *       包装（{@code AbstractTrustManagerWrapper}），那层在信任链通过之后**仍会**做
 *       endpoint identification；换成扩展版后按接口契约由实现方自己负责校验，
 *       包装层不再介入。实测同一台 BMC：普通版 = {@code SSLHandshakeException:
 *       (certificate_unknown) No subject alternative names matching IP address 10.202.54.11}，
 *       扩展版 = HTTP 200。</li>
 * </ul>
 *
 * <p>为什么不用另外两条更省事的路：
 * <ul>
 *   <li>{@code SSLParameters.setEndpointIdentificationAlgorithm(null/"")} 对
 *       {@code java.net.http.HttpClient} 无效（JDK 21 三种写法实测全部照样握手失败）；</li>
 *   <li>JVM 级 {@code jdk.internal.httpclient.disableHostnameVerification=true} 会连带
 *       削弱本进程**所有**出站 HTTPS（含大模型调用），代价远超收益；</li>
 *   <li>{@code HttpsURLConnection} 的 {@code setHostnameVerifier} 倒是能放开主机名，
 *       但该实现对 {@code Authorization} 头做了过滤（实测连
 *       {@code -Dsun.net.http.allowRestrictedHeaders=true} 都设不进去），
 *       Redfish 的 Basic 认证只能改走全局 {@code Authenticator}，又变成新的全局副作用。</li>
 * </ul>
 *
 * <p>这是有意的取舍：本通道只负责在**管理网内**取只读清单，不承担「对面是不是真 BMC」
 * 的判定；身份真实性由资产登记与网络隔离保证。
 */
@Component
public class BmcRedfishCollector {

    public static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** 分类名：`BMC_` 前缀与 Linux（10 类）和网络设备（NET_ 前缀）区分。 */
    public static final Map<String, String> LABELS = Map.of(
            "BMC_MANUFACTURER", "BMC 厂商",
            "BMC_MODEL", "服务器型号",
            "BMC_SERIAL", "序列号",
            "BMC_POWER", "电源状态",
            "BMC_HEALTH", "健康状态",
            "BMC_BIOS", "BIOS 版本");

    private static final String SYSTEMS = "/redfish/v1/Systems";
    private static final String SYSTEM_1 = "/redfish/v1/Systems/1";

    private final ObjectMapper mapper;
    private final HttpClient http;

    public BmcRedfishCollector(ObjectMapper mapper) {
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(context())
                .build();
    }

    public HostInventory collect(String host, Long deviceId, HostAccess access) {
        Instant started = Instant.now();
        if (access == null || access.secret() == null || access.secret().isBlank()) {
            List<HostInventoryItem> items = allMissing("BMC 采集缺少可用凭据（需 CREDENTIAL:USE 且已配置口令）");
            return new HostInventory(host, deviceId, started, HostInventory.STATUS_FAILED, items);
        }
        String auth = basic(access.username(), access.secret());
        HttpOutcome system = fetchSystem(host, auth);
        if (!system.ok()) {
            List<HostInventoryItem> items = allMissing(system.error());
            return new HostInventory(host, deviceId, started, HostInventory.STATUS_FAILED, items);
        }

        JsonNode body = system.body();
        List<HostInventoryItem> items = new ArrayList<>();
        String command = "GET https://" + host + system.path();
        for (Map.Entry<String, String> entry : parseSystem(body).entrySet()) {
            items.add(item(entry.getKey(), command, entry.getValue()));
        }
        return new HostInventory(host, deviceId, started, HostInventory.statusOf(items), items);
    }

    /**
     * 从 Systems 响应里取 6 个标准字段（缺失即 null，不填默认值）。
     *
     * <p>抽成静态方法是为了离线单测字段映射：Redfish 的字段名在不同 BMC 上一致，
     * 但**有没有**这些字段不一致（浪潮 v1_5_2 没 BiosVersion，Dell 有），
     * 用两份真实响应做夹具比连两次真机更能覆盖分支。
     */
    public static Map<String, String> parseSystem(JsonNode body) {
        Map<String, String> values = new java.util.LinkedHashMap<>();
        values.put("BMC_MANUFACTURER", text(body, "Manufacturer"));
        values.put("BMC_MODEL", text(body, "Model"));
        values.put("BMC_SERIAL", text(body, "SerialNumber"));
        values.put("BMC_POWER", text(body, "PowerState"));
        values.put("BMC_HEALTH", body == null ? null : body.path("Status").path("Health").asText(null));
        values.put("BMC_BIOS", text(body, "BiosVersion"));
        return values;
    }

    /**
     * 先取 Systems 集合拿第一条的 {@code @odata.id}，取不到再退回固定的 {@code /Systems/1}。
     *
     * <p>为什么不一上来就 {@code /Systems/1}：Dell iDRAC 的成员 id 是
     * {@code System.Embedded.1}，写死 {@code /Systems/1} 在它上面会 404，而错误会被
     * 归类成「不可达」，把「路径不对」伪装成「网络不通」。
     */
    private HttpOutcome fetchSystem(String host, String auth) {
        HttpOutcome collection = get(host, SYSTEMS, auth);
        if (collection.ok()) {
            String member = collection.body().path("Members").path(0).path("@odata.id").asText(null);
            if (member != null && !member.isBlank()) {
                return get(host, member, auth);
            }
        }
        // 集合取不到（有的 BMC 只开了成员接口）→ 退回 /Systems/1
        return get(host, SYSTEM_1, auth);
    }

    private HttpOutcome get(String host, String path, String auth) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://" + host + path))
                    .timeout(TIMEOUT)
                    .header("Authorization", "Basic " + auth)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                return HttpOutcome.fail(path, CollectFailure.AUTH + ": HTTP " + status
                        + "（Redfish 认证被拒，账号或口令不符）");
            }
            if (status >= 400) {
                return HttpOutcome.fail(path, CollectFailure.COMMAND + ": HTTP " + status
                        + "（Redfish 路径不被支持：" + path + "）");
            }
            return HttpOutcome.ok(path, mapper.readTree(response.body()));
        } catch (HttpTimeoutException ex) {
            return HttpOutcome.fail(path, CollectFailure.UNREACHABLE + ": 连接超时（>" + TIMEOUT.toSeconds() + "s）");
        } catch (ConnectException ex) {
            return HttpOutcome.fail(path, CollectFailure.UNREACHABLE + ": 无法连接（" + ex.getMessage() + "）");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return HttpOutcome.fail(path, CollectFailure.UNREACHABLE + ": 采集被中断");
        } catch (Exception ex) {
            String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            return HttpOutcome.fail(path, CollectFailure.classify(message) + ": " + message);
        }
    }

    private static HostInventoryItem item(String category, String command, String value) {
        if (value == null || value.isBlank()) {
            return HostInventoryItem.missing(category, LABELS.get(category), command, Instant.now(),
                    "Redfish 未返回该字段（端点版本差异，属可接受缺失）");
        }
        return HostInventoryItem.ok(category, LABELS.get(category), command, Instant.now(), value);
    }

    /** 固定顺序：`Map.of` 不保序，报告与证据按固定顺序读，不能用 entrySet 迭代。 */
    private static final List<String> CATEGORIES = List.of(
            "BMC_MANUFACTURER", "BMC_MODEL", "BMC_SERIAL", "BMC_POWER", "BMC_HEALTH", "BMC_BIOS");

    private static List<HostInventoryItem> allMissing(String reason) {
        List<HostInventoryItem> items = new ArrayList<>();
        for (String category : CATEGORIES) {
            items.add(HostInventoryItem.missing(category, LABELS.get(category),
                    "GET /redfish/v1/Systems", Instant.now(), reason));
        }
        return items;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String basic(String username, String secret) {
        String user = username == null ? "" : username;
        return Base64.getEncoder()
                .encodeToString((user + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static SSLContext context() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {trustManager()}, new SecureRandom());
            return ctx;
        } catch (Exception ex) {
            throw new IllegalStateException("初始化 Redfish TLS 上下文失败：" + ex.getMessage(), ex);
        }
    }

    /**
     * 全放行的信任管理器。**必须是 {@link X509ExtendedTrustManager}**，
     * 换成普通的 {@code X509TrustManager} 会被 JSSE 套上身份校验包装层，
     * BMC 自签证书的 SAN（厂商内部地址）对不上管理网 IP，握手直接失败。
     */
    static X509ExtendedTrustManager trustManager() {
        return new X509ExtendedTrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
                // BMC 自签证书：本通道不校验对端身份，见类注释
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
                // 同上
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
                // 同上
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
                // 同上
            }

            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 同上
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // 同上
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private record HttpOutcome(boolean ok, String path, JsonNode body, String error) {

        static HttpOutcome ok(String path, JsonNode body) {
            return new HttpOutcome(true, path, body, null);
        }

        static HttpOutcome fail(String path, String error) {
            return new HttpOutcome(false, path, null, error);
        }
    }
}
