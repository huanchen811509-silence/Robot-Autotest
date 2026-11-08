package com.qa.autotest.application.caseasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.qa.autotest.application.repository.ExecutionSelectionRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.domain.caseasset.FrozenSelection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** SCEN-02：执行选择冻结的纯单测（内存仓储替身，不连数据库）。 */
class ExecutionSelectionFreezeTest {

    private static final String SERVICE_SRC =
            "src/main/java/com/qa/autotest/application/caseasset/ExecutionSelectionService.java";
    private static final String JDBC_SRC =
            "src/main/java/com/qa/autotest/infrastructure/ExecutionSelectionRepositoryJdbc.java";

    /** 只记录写入、不提供任何改写入口的内存替身。 */
    private static final class MemoryRepository implements ExecutionSelectionRepository {
        final List<FrozenSelection> inserted = new ArrayList<>();

        @Override
        public long insert(FrozenSelection selection) {
            long id = inserted.size() + 1L;
            inserted.add(new FrozenSelection(id, selection.projectId(), selection.designId(),
                    selection.payloadJson(), selection.resourceSnapshotJson(),
                    selection.sha256(), selection.frozenAt()));
            return id;
        }

        @Override
        public Optional<FrozenSelection> find(long projectId, long id) {
            return inserted.stream()
                    .filter(s -> s.projectId() == projectId && s.id() == id)
                    .findFirst();
        }
    }

    /** 来源设计快照：不可变 record，携带 updatedAt 用于冻结前后比对。 */
    private record Design(String setCode, String name, String description, String updatedAt) {
    }

    private static Path repoFile(String relative) {
        Path fromBackend = Path.of("..", relative);
        return Files.isRegularFile(fromBackend) ? fromBackend : Path.of(relative);
    }

    private static String source(String relative) throws Exception {
        return Files.readString(repoFile(relative));
    }

    @Test
    void 同一设计两次冻结SHA不同且都只INSERT() {
        MemoryRepository repo = new MemoryRepository();
        ExecutionSelectionService service = new ExecutionSelectionService(repo);
        FrozenSelection first = service.freeze(7L, 42L, "{\"cases\":[1]}", "{\"gpu\":8}");
        FrozenSelection second = service.freeze(7L, 42L, "{\"cases\":[1]}", "{\"gpu\":8}");
        assertThat(first.sha256()).isNotEqualTo(second.sha256());
        assertThat(first.sha256()).hasSize(64);
        assertThat(second.sha256()).hasSize(64);
        assertThat(repo.inserted).hasSize(2);
        assertThat(service.find(7L, 1L)).isPresent();
        assertThat(first.designId()).isEqualTo(42L);
        assertThat(second.designId()).isEqualTo(42L);
    }

    @Test
    void 冻结不改来源设计内容与updatedAt() throws Exception {
        Design design = new Design("SET-42", "来源设计", "原始描述", "2026-11-08T10:00:00Z");
        String contentBefore = design.setCode() + "/" + design.name() + "/" + design.description();
        String updatedAtBefore = design.updatedAt();

        MemoryRepository repo = new MemoryRepository();
        ExecutionSelectionService service = new ExecutionSelectionService(repo);
        service.freeze(7L, 42L, "payload", "snapshot");
        service.freeze(7L, 42L, "payload", "snapshot");

        assertThat(design).isEqualTo(new Design("SET-42", "来源设计", "原始描述",
                "2026-11-08T10:00:00Z"));
        assertThat(design.setCode() + "/" + design.name() + "/" + design.description())
                .isEqualTo(contentBefore);
        assertThat(design.updatedAt()).isEqualTo(updatedAtBefore);
        assertThat(source(SERVICE_SRC)).doesNotContain("UPDATE test_set", "INSERT INTO test_set");
    }

    @Test
    void 冻结源码只发INSERT不含改写() throws Exception {
        String service = source(SERVICE_SRC);
        String jdbc = source(JDBC_SRC);
        assertThat(service).doesNotContain("UPDATE", "DELETE");
        assertThat(jdbc).doesNotContain("UPDATE", "DELETE");
        assertThat(jdbc).contains("INSERT INTO execution_selection");
    }

    @Test
    void 迁移含唯一键与三态白名单收口() throws Exception {
        String migration = source("db/migration/V11_12__execution_selection.sql");
        assertThat(migration).contains("uk_execution_selection_sha", "sha256");

        TestSetService sets = new TestSetService(null, null, null, null, null);
        for (String bad : List.of("bogus", "CASE_SET", "", "execution_selections")) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> sets.validateKind(bad));
            assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);
            assertThat(e.getMessage()).contains("INVALID_ARGUMENT", "三态");
        }
        assertThrows(BusinessException.class, () -> sets.validateKind(null));
        assertDoesNotThrow(() -> sets.validateKind("case_set"));
        assertDoesNotThrow(() -> sets.validateKind("scenario"));
        assertDoesNotThrow(() -> sets.validateKind("execution_selection"));
    }

    @Test
    void 三态创建SQL显式写kind与designId() throws Exception {
        String setsSource = source(
                "src/main/java/com/qa/autotest/application/caseasset/TestSetService.java");
        assertThat(setsSource).contains("ALLOWED_KINDS", "validateKind", "insertWithKind");
        String setsJdbc = source(
                "src/main/java/com/qa/autotest/infrastructure/TestSetRepositoryJdbc.java");
        assertThat(setsJdbc).contains("insertWithKind", "kind, design_id", "set_code", "name");
    }
}
