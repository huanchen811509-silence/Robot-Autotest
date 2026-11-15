package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.CaseScriptBindingRepository;
import com.qa.autotest.domain.caseasset.CaseScriptBinding;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * v5.11.0 / W61-11：一页用例的脚本解析（FR-v5.11-05 判据 5「多内核按项目内核优先」）。
 *
 * <p>选错内核的后果不是「少显示一个路径」，而是**显示成另一个内核会跑的那份脚本**——
 * 用户照着去排查，方向从一开始就是歪的。所以这条优先级单独锁死。
 */
class CaseScriptLookupTest {

    private CaseScriptBindingRepository bindings;
    private JdbcTemplate jdbc;
    private CaseScriptLookup lookup;

    @BeforeEach
    void setUp() {
        bindings = mock(CaseScriptBindingRepository.class);
        jdbc = mock(JdbcTemplate.class);
        lookup = new CaseScriptLookup(bindings, jdbc);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(7L)))
                .thenReturn(List.of("pytest"));
    }

    private static CaseScriptBinding binding(long id, long caseId, String kernel, String path,
                                             boolean verified) {
        return new CaseScriptBinding(id, 7L, caseId, kernel, path, "node::x", "sha",
                CaseScriptBinding.SOURCE_DOCSTRING, verified);
    }

    @SuppressWarnings("unchecked")
    private void givenBindings(List<CaseScriptBinding> rows) {
        when(bindings.listByCaseIds(eq(7L), any(Collection.class))).thenReturn(rows);
    }

    @Test
    @DisplayName("同一条用例两份绑定时，选项目内核（pytest）那份，并给出 otherScriptCount=1")
    void prefersProjectKernel() {
        givenBindings(List.of(
                binding(2L, 11L, "robot", "suites/robot/net.robot", false),
                binding(1L, 11L, "pytest", "tests/net/test_bw.py", true)));

        Map<Long, CaseScriptView> map = lookup.of(7L, List.of(11L));

        assertThat(map.get(11L).scriptPath()).isEqualTo("tests/net/test_bw.py");
        assertThat(map.get(11L).scriptName()).isEqualTo("test_bw.py");
        assertThat(map.get(11L).kernel()).isEqualTo("pytest");
        assertThat(map.get(11L).otherScriptCount()).isEqualTo(1);
        assertThat(map.get(11L).bindingState()).isEqualTo(CaseScriptView.BOUND_VERIFIED);
    }

    @Test
    @DisplayName("只有别的内核的绑定时照实显示，不退回「未绑定」")
    void fallsBackToOtherKernel() {
        givenBindings(List.of(binding(2L, 11L, "robot", "suites/robot/net.robot", false)));

        CaseScriptView view = lookup.of(7L, List.of(11L)).get(11L);

        assertThat(view.kernel()).isEqualTo("robot");
        assertThat(view.scriptPath()).isEqualTo("suites/robot/net.robot");
        assertThat(view.bindingState()).isEqualTo(CaseScriptView.BOUND_UNVERIFIED);
        assertThat(view.otherScriptCount()).isZero();
    }

    @Test
    @DisplayName("没有绑定的用例：pytest 项目给 UNBOUND，robot 项目给 NOT_APPLICABLE")
    void emptyWhenNoBinding() {
        givenBindings(List.of());

        assertThat(lookup.of(7L, List.of(11L)).get(11L).bindingState())
                .isEqualTo(CaseScriptView.UNBOUND);

        when(jdbc.queryForList(anyString(), eq(String.class), eq(7L)))
                .thenReturn(List.of("robot"));
        assertThat(lookup.of(7L, List.of(11L)).get(11L).bindingState())
                .isEqualTo(CaseScriptView.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("项目行查不到内核时按 robot 兜底，不抛异常")
    void kernelDefaultsToRobot() {
        when(jdbc.queryForList(anyString(), eq(String.class), anyLong())).thenReturn(List.of());

        assertThat(lookup.kernelOf(99L)).isEqualTo("robot");
    }

    @Test
    @DisplayName("空 id 集合直接返回空 Map，不打库")
    void emptyIdsShortCircuits() {
        assertThat(lookup.of(7L, List.of())).isEmpty();
        assertThat(lookup.of(7L, null)).isEmpty();
    }
}
