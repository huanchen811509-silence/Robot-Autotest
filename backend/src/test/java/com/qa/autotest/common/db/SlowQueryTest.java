package com.qa.autotest.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** FR-3.2-02 / FR-3.2-07：慢查询阈值与 SQL 指纹（不落参数值）。 */
class SlowQueryTest {

    @Test
    void thresholdIsWarnAbove200AndErrorAbove500() {
        assertThat(SlowQuery.levelFor(199)).isNull();
        assertThat(SlowQuery.levelFor(200)).isNull();
        assertThat(SlowQuery.levelFor(201)).isEqualTo("WARN");
        assertThat(SlowQuery.levelFor(500)).isEqualTo("WARN");
        assertThat(SlowQuery.levelFor(501)).isEqualTo("ERROR");
    }

    @Test
    void fingerprintIgnoresWhitespaceAndLiterals() {
        String a = "SELECT * FROM test_case WHERE project_id = 29 AND title = '登录用例'";
        String b = "select *\n from test_case where project_id=29 and title='登录用例'";
        assertThat(SlowQuery.fingerprint(a)).isEqualTo(SlowQuery.fingerprint(b));
    }

    @Test
    void fingerprintDiffersForDifferentShape() {
        assertThat(SlowQuery.fingerprint("SELECT id FROM a WHERE id = ?"))
                .isNotEqualTo(SlowQuery.fingerprint("SELECT title FROM a WHERE id = ?"));
    }

    @Test
    void fingerprintOfEmptyIsStable() {
        assertThat(SlowQuery.fingerprint(null)).isEqualTo("empty");
        assertThat(SlowQuery.fingerprint(" ")).isEqualTo("empty");
    }

    @Test
    void measureReturnsResultAndRethrows() {
        List<String> value = SlowQuery.measure("unit.test", "SELECT 1", () -> List.of("ok"));
        assertThat(value).containsExactly("ok");
        RuntimeException ex = null;
        try {
            SlowQuery.measure("unit.test", "SELECT 1", () -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException caught) {
            ex = caught;
        }
        assertThat(ex).hasMessage("boom");
    }
}
