package com.qa.autotest.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qa.autotest.application.repository.TestCaseRepository;
import com.qa.autotest.infrastructure.TestCaseRepositoryJdbc;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * v5.11.0 / W61-16：导入写入的**跨项目收口**（FR-v5.11-09）。
 *
 * <p>原实现只按用例 id 更新/插入，安全性押在「调用点恰好刚插入同事务 id」上。这里锁两件事：
 * ① 元数据 UPDATE 自带 {@code project_id} 条件；② 步骤 INSERT 用 {@code WHERE EXISTS}
 * 把父用例的租户钉住（{@code test_case_step} 没有 project_id 列，这是等价写法）。
 */
class ImportWriteScopeTest {

    private JdbcTemplate jdbc;
    private TestCaseRepositoryJdbc repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        repository = new TestCaseRepositoryJdbc(jdbc);
    }

    @Test
    @DisplayName("元数据 UPDATE 带 project_id，且 id 在前、project_id 在后")
    void metadataUpdateCarriesProjectId() {
        TestCaseRepository.ImportMetadata meta = new TestCaseRepository.ImportMetadata(
                "L2", "P0", "class", "category", "coverage", "sheet");

        repository.writeImportMetadata(7L, 5L, meta, 1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("WHERE id = ? AND project_id = ?");
        Object[] values = args.getValue();
        assertThat(values[values.length - 2]).isEqualTo(5L);
        assertThat(values[values.length - 1]).isEqualTo(7L);
    }

    @Test
    @DisplayName("跨项目 id：UPDATE 影响 0 行（条件不成立，不会误改他项目数据）")
    void metadataUpdateTouchesZeroRowsWhenProjectMismatched() {
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);
        TestCaseRepository.ImportMetadata meta = new TestCaseRepository.ImportMetadata(
                "L2", null, null, null, null, null);

        repository.writeImportMetadata(999L, 5L, meta, 1L);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()[args.getValue().length - 1]).isEqualTo(999L);
    }

    @Test
    @DisplayName("步骤 INSERT 用 EXISTS 钉住父用例的 project_id（表本身无该列）")
    void stepsInsertGuardedByParentProject() {
        repository.insertImportedSteps(7L, 5L,
                List.of(new TestCaseRepository.ImportedStep(1, "动作", "预期")), 12, 1L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("WHERE EXISTS (SELECT 1 FROM test_case WHERE id = ? AND project_id = ?)");
        Object[] values = args.getValue();
        assertThat(values[values.length - 2]).isEqualTo(5L);
        assertThat(values[values.length - 1]).isEqualTo(7L);
    }

    @Test
    @DisplayName("没有步骤或元数据全空：一条 SQL 都不发（空写不算写）")
    void skipsEmptyPayloads() {
        repository.insertImportedSteps(7L, 5L, List.of(), 1, 1L);
        repository.writeImportMetadata(7L, 5L,
                new TestCaseRepository.ImportMetadata(null, null, null, null, null, null), 1L);

        verify(jdbc, org.mockito.Mockito.never()).update(anyString(), any(Object[].class));
    }
}
