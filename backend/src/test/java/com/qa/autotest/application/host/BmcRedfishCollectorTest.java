package com.qa.autotest.application.host;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.domain.host.HostInventory;
import java.security.cert.X509Certificate;
import java.util.Map;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BMC Redfish 采集器离线验收（v4.12.0 / FR-v4.12-08）。
 *
 * <p>夹具按两台**现场真机**的响应形状手写（浪潮 NF5468M7 / Redfish v1_5_2，
 * Dell R750 / v1_17_0）：两家都返回 `Manufacturer/Model/SerialNumber/PowerState`，
 * 但 Dell 多 `BiosVersion`、浪潮的 `Status.Health` 偶尔缺。**不连真机**也能覆盖
 * 「字段有 / 字段没有」两条分支，这是把字段映射抽成静态方法的原因。
 */
class BmcRedfishCollectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 浪潮 BMC（v1_5_2）：无 BiosVersion。 */
    private static final String INSPUR_SYSTEM = """
            {
              "@odata.id": "/redfish/v1/Systems/1",
              "Manufacturer": "Inspur",
              "Model": "NF5468M7",
              "SerialNumber": "220800A12345",
              "PowerState": "On",
              "Status": {"Health": "OK", "State": "Enabled"}
            }
            """;

    /** Dell iDRAC（v1_17_0）：有 BiosVersion，成员 id 是 System.Embedded.1。 */
    private static final String DELL_SYSTEM = """
            {
              "@odata.id": "/redfish/v1/Systems/System.Embedded.1",
              "Manufacturer": "Dell Inc.",
              "Model": "PowerEdge R750",
              "SerialNumber": "CN7K2M3",
              "PowerState": "On",
              "BiosVersion": "1.9.2",
              "Status": {"Health": "Warning", "State": "Enabled"}
            }
            """;

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    @DisplayName("浪潮：6 个字段映射正确，缺失的 BiosVersion 保持 null 而不是填占位值")
    void inspurFields() throws Exception {
        Map<String, String> values = BmcRedfishCollector.parseSystem(json(INSPUR_SYSTEM));

        assertThat(values).containsEntry("BMC_MANUFACTURER", "Inspur")
                .containsEntry("BMC_MODEL", "NF5468M7")
                .containsEntry("BMC_SERIAL", "220800A12345")
                .containsEntry("BMC_POWER", "On")
                .containsEntry("BMC_HEALTH", "OK");
        assertThat(values.get("BMC_BIOS")).isNull();
    }

    @Test
    @DisplayName("Dell：健康状态可以是 Warning，不能把非 OK 当成采集失败")
    void dellFields() throws Exception {
        Map<String, String> values = BmcRedfishCollector.parseSystem(json(DELL_SYSTEM));

        assertThat(values).containsEntry("BMC_MODEL", "PowerEdge R750")
                .containsEntry("BMC_HEALTH", "Warning")
                .containsEntry("BMC_BIOS", "1.9.2");
    }

    @Test
    @DisplayName("字段顺序固定（报告与证据按固定顺序读）")
    void fieldOrderIsStable() throws Exception {
        assertThat(BmcRedfishCollector.parseSystem(json(DELL_SYSTEM)).keySet())
                .containsExactly("BMC_MANUFACTURER", "BMC_MODEL", "BMC_SERIAL",
                        "BMC_POWER", "BMC_HEALTH", "BMC_BIOS");
    }

    @Test
    @DisplayName("空响应体不抛异常，全部字段为 null")
    void emptyBody() {
        Map<String, String> values = BmcRedfishCollector.parseSystem(null);
        assertThat(values.values()).allMatch(java.util.Objects::isNull);
        assertThat(values).hasSize(6);
    }

    @Test
    @DisplayName("没有凭据时不发请求，直接 FAILED 且 6 项都给原因")
    void missingSecretFailsFast() {
        BmcRedfishCollector collector = new BmcRedfishCollector(MAPPER);
        HostInventory inventory = collector.collect("10.202.54.11", 6L,
                new HostAccess(1L, "admin", 3L, "", "bmc", null));

        assertThat(inventory.status()).isEqualTo(HostInventory.STATUS_FAILED);
        assertThat(inventory.items()).hasSize(6);
        assertThat(inventory.items()).allSatisfy(item -> {
            assertThat(item.collected()).isFalse();
            assertThat(item.reason()).contains("凭据");
        });
    }

    /**
     * 回归自现场事故：5 台 BMC 全报
     * `SSLHandshakeException: (certificate_unknown) No subject alternative names matching IP address 10.202.54.11`。
     *
     * <p>根因是**主机名校验**而不是信任链——现场浪潮证书 SAN 只有厂商内部地址
     * （`DNS:megarac.com, IP:169.254.0.17`），用管理网 IP 连必然不匹配。
     * 而 JSSE 会给普通的 `X509TrustManager` 套一层身份校验包装
     * （信任链放行完再查 hostname），只有 `X509ExtendedTrustManager` 才按契约
     * 由实现方自己负责校验。这条断言锁的就是「不能退回普通 X509TrustManager」。
     */
    @Test
    @DisplayName("信任管理器必须是扩展版，且三类校验全放行")
    void trustManagerBypassesHostnameCheck() throws Exception {
        X509ExtendedTrustManager manager = BmcRedfishCollector.trustManager();

        assertThat(manager).isInstanceOf(X509ExtendedTrustManager.class);
        assertThat(manager.getAcceptedIssuers()).isEmpty();
        // 三条重载（无上下文 / Socket / SSLEngine）都必须不抛，任一条抛就等于握手失败
        manager.checkServerTrusted(new X509Certificate[0], "RSA");
        manager.checkServerTrusted(new X509Certificate[0], "RSA", (java.net.Socket) null);
        manager.checkServerTrusted(new X509Certificate[0], "RSA", (javax.net.ssl.SSLEngine) null);
        manager.checkClientTrusted(new X509Certificate[0], "RSA");
    }
}
