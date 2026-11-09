package com.qa.autotest.application.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.qa.autotest.domain.execution.RunState;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 进度 ETA 与卡死判定：只做纯计算，不依赖仓储。 */
class RunEtaCalculatorTest {

    @Test
    @DisplayName("按已完成的平均速度外推剩余时间")
    void 计算ETA() {
        assertThat(RunEtaCalculator.etaSeconds(10, 4, 20, false)).isEqualTo(30L);
    }

    @Test
    @DisplayName("无样本 / 已结束 / 已完成到总数时不硬造数字")
    void ETA边界() {
        assertThat(RunEtaCalculator.etaSeconds(0, 0, 30, false)).isNull();
        assertThat(RunEtaCalculator.etaSeconds(5, 0, 30, false)).isNull();
        assertThat(RunEtaCalculator.etaSeconds(5, 1, 0, false)).isNull();
        assertThat(RunEtaCalculator.etaSeconds(5, 5, 30, false)).isZero();
        assertThat(RunEtaCalculator.etaSeconds(5, 2, 30, true)).isZero();
    }

    @Test
    @DisplayName("心跳年龄允许时钟回拨，最小为 0")
    void 心跳年龄() {
        Instant now = Instant.parse("2026-11-08T10:00:00Z");
        assertThat(RunEtaCalculator.heartbeatAgeSeconds(
                now.minusSeconds(70), now)).isEqualTo(70L);
        assertThat(RunEtaCalculator.heartbeatAgeSeconds(now.plusSeconds(10), now)).isZero();
        assertThat(RunEtaCalculator.heartbeatAgeSeconds(null, now)).isNull();
    }

    @Test
    @DisplayName("只有 RUNNING 且超过阈值才算卡死；阈值下限 1 秒")
    void 卡死判定() {
        assertThat(RunEtaCalculator.stalled(RunState.RUNNING, 61L, 60)).isTrue();
        assertThat(RunEtaCalculator.stalled(RunState.RUNNING, 60L, 60)).isFalse();
        assertThat(RunEtaCalculator.stalled(RunState.FINISHED, 61L, 60)).isFalse();
        assertThat(RunEtaCalculator.stalled(RunState.RUNNING, 1L, 0)).isFalse();
        assertThat(RunEtaCalculator.stalled(RunState.RUNNING, 2L, 0)).isTrue();
        assertThat(RunEtaCalculator.stalled(RunState.RUNNING, null, 60)).isFalse();
    }
}
