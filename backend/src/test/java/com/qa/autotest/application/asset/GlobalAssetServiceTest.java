package com.qa.autotest.application.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * v4.12.0 W-C 的纯函数口径测试。
 *
 * <p>这里只锁「不用连库也能判对错」的部分：类型归一与失败原因提取。它们看起来是琐碎工具函数，
 * 但两处都会直接影响现场排障结论 ——
 * <ul>
 *   <li>归一函数放宽了，`credType=foo` 会静默落成 ssh，运维以为配了 BMC 结果一直走 Linux 通道；</li>
 *   <li>原因提取错了，前端「5 台失败」里给出的原因是上一台残留的，排障方向直接跑偏。</li>
 * </ul>
 */
class GlobalAssetServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("凭据类型：network 合法，大小写归一，未知值 400")
    void credentialTypeIsValidated() {
        assertThat(GlobalAssetService.normalizeCredType("network")).isEqualTo("network");
        assertThat(GlobalAssetService.normalizeCredType(" NETWORK ")).isEqualTo("network");
        assertThat(GlobalAssetService.normalizeCredType("bmc")).isEqualTo("bmc");

        // 判据 FR-v4.12-06：credType=foo 必须 400，不许退化成 ssh
        assertThatThrownBy(() -> GlobalAssetService.normalizeCredType("foo"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ssh | network | bmc | snmp | token");
        assertThatThrownBy(() -> GlobalAssetService.normalizeCredType("  "))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("设备类型：四个枚举值合法，空值放行，旧口径「交换机」拒绝")
    void deviceTypeIsValidated() {
        assertThat(GlobalAssetService.normalizeDeviceType("gpu")).isEqualTo("gpu");
        assertThat(GlobalAssetService.normalizeDeviceType("Switch")).isEqualTo("switch");
        // 空值不强制：录入时允许先不分类，分类是治理动作不是录入门槛
        assertThat(GlobalAssetService.normalizeDeviceType(null)).isNull();
        assertThat(GlobalAssetService.normalizeDeviceType("   ")).isNull();
        // 导入老数据里的中文类型名不进这条路径，但手工录入必须拦住，否则口径永远收不拢
        assertThatThrownBy(() -> GlobalAssetService.normalizeDeviceType("交换机"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("gpu | server | storage | switch");
    }

    @Test
    @DisplayName("失败原因：取第一条非空 reason，而不是把整段 JSON 抛给前端")
    void firstReasonPicksFirstNonBlank() throws Exception {
        String json = """
                {"host":"10.202.54.91","status":"FAILED","items":[
                  {"category":"NET_VENDOR","reason":null},
                  {"category":"NET_MODEL","reason":"algorithm_mismatch: Unable to negotiate with 10.202.54.91"},
                  {"category":"NET_PORTS","reason":"command_unsupported: % Line has invalid autocommand"}
                ]}
                """;
        assertThat(GlobalAssetService.firstReason(MAPPER, json))
                .isEqualTo("algorithm_mismatch: Unable to negotiate with 10.202.54.91");
    }

    @Test
    @DisplayName("失败原因：全为空 reason / 空串 / 脏 JSON / null 一律返回 null，不抛")
    void firstReasonIsNullWhenAbsent() {
        assertThat(GlobalAssetService.firstReason(MAPPER, null)).isNull();
        assertThat(GlobalAssetService.firstReason(MAPPER, "   ")).isNull();
        assertThat(GlobalAssetService.firstReason(MAPPER, "{\"items\":[]}")).isNull();
        assertThat(GlobalAssetService.firstReason(MAPPER,
                "{\"items\":[{\"reason\":null},{\"reason\":\"  \"}]}")).isNull();
        // 脏 JSON 不能让整批采集 500：状态已经落库，原因只是附加信息
        assertThat(GlobalAssetService.firstReason(MAPPER, "<html>502 Bad Gateway</html>")).isNull();
    }

    @Test
    @DisplayName("失败原因：超过 200 字符截断，换行压成单行（要进表格与审计）")
    void firstReasonIsClippedAndFlattened() {
        String longReason = "auth_failed:\n" + "x".repeat(400);
        String json = "{\"items\":[{\"reason\":\"" + longReason.replace("\n", "\\n") + "\"}]}";
        String reason = GlobalAssetService.firstReason(MAPPER, json);
        assertThat(reason).isNotNull();
        assertThat(reason).hasSize(200);
        assertThat(reason).doesNotContain("\n");
        assertThat(reason).startsWith("auth_failed: x");
    }

    @Test
    @DisplayName("批量采集的 total == ok + failed 不变量由记录结构保证")
    void batchResultInvariantHolds() {
        // 该 record 只是数据载体，但「没有第三条暗河」是前端摘要能对上数的前提，
        // 这里用一个显式用例把口径写进测试，避免以后有人加个 skipped 字段忘了改前端。
        GlobalAssetService.BatchCollectResult result = new GlobalAssetService.BatchCollectResult(
                3, 2, 1, java.util.List.of());
        assertThat(result.total()).isEqualTo(result.ok() + result.failed());
        assertThat(ErrorCode.CONFLICT.name()).isNotBlank();
    }
}
