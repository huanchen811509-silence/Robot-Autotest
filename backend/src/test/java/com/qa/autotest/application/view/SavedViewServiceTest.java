package com.qa.autotest.application.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.SavedViewRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 保存视图：同名冲突、默认位唯一、payload 约束与「只能操作自己的视图」。 */
class SavedViewServiceTest {

    private static final long PROJECT = 1L;
    private static final long USER = 7L;

    private FakeSavedViewRepository repository;
    private SavedViewService service;

    @BeforeEach
    void setUp() {
        repository = new FakeSavedViewRepository();
        AccessControlService access = mock(AccessControlService.class);
        doNothing().when(access).bindProject(anyLong());
        TenantContext.set(USER, "tester", PROJECT);
        service = new SavedViewService(repository, access, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("同名同作用域第二次创建返回 409")
    void 同名冲突() {
        service.create(PROJECT, new SavedViewService.SaveRequest("我的视图", "case", Map.of(), false));

        assertThatThrownBy(() -> service.create(PROJECT,
                new SavedViewService.SaveRequest("我的视图", "case", Map.of(), false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    @DisplayName("设为默认时，同用户同作用域的其他视图默认位被清掉")
    void 默认位唯一() {
        SavedView first = service.create(PROJECT,
                new SavedViewService.SaveRequest("全部", "case", Map.of("page", 1), true));
        SavedView second = service.create(PROJECT,
                new SavedViewService.SaveRequest("我的", "case", Map.of("page", 2), true));

        assertThat(repository.find(PROJECT, first.id(), USER).orElseThrow().isDefault()).isFalse();
        assertThat(second.isDefault()).isTrue();
        assertThat(repository.defaultCount(USER, "case")).isEqualTo(1);
    }

    @Test
    @DisplayName("payload 必须是 JSON 对象")
    void payload必须是对象() {
        assertThatThrownBy(() -> service.create(PROJECT,
                new SavedViewService.SaveRequest("x", "case", "not-an-object", false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("payload 序列化后超过 8KB 被拒绝")
    void payload超限() {
        String huge = "x".repeat(SavedViewService.MAX_PAYLOAD_BYTES);

        assertThatThrownBy(() -> service.create(PROJECT,
                new SavedViewService.SaveRequest("x", "case", Map.of("blob", huge), false)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT));
    }

    @Test
    @DisplayName("不能删除别人的视图：拿不到自己的记录即 404")
    void 不能操作他人视图() {
        repository.insert(new SavedView(0, PROJECT, USER + 1, "别人的", "case",
                Map.of(), false, Instant.now(), Instant.now()));

        assertThatThrownBy(() -> service.delete(PROJECT, 1L))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    /** 内存假仓储：id 自增，按 project_id + user_id 双隔离。 */
    private static final class FakeSavedViewRepository implements SavedViewRepository {

        private final Map<Long, SavedView> rows = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public List<SavedView> list(long projectId, long userId, String scope) {
            List<SavedView> result = new ArrayList<>();
            for (SavedView view : rows.values()) {
                if (view.projectId() == projectId && view.userId() == userId
                        && view.scope().equals(scope)) {
                    result.add(view);
                }
            }
            return result;
        }

        @Override
        public Optional<SavedView> find(long projectId, long id, long userId) {
            SavedView view = rows.get(id);
            return view != null && view.projectId() == projectId && view.userId() == userId
                    ? Optional.of(view) : Optional.empty();
        }

        @Override
        public long insert(SavedView view) {
            long id = nextId++;
            rows.put(id, new SavedView(id, view.projectId(), view.userId(), view.name(),
                    view.scope(), view.payload(), view.isDefault(), Instant.now(), Instant.now()));
            return id;
        }

        @Override
        public void update(SavedView view) {
            rows.put(view.id(), view);
        }

        @Override
        public void delete(long projectId, long id, long userId) {
            rows.remove(id);
        }

        @Override
        public boolean nameExists(long projectId, long userId, String scope, String name,
                                  Long excludeId) {
            return rows.values().stream().anyMatch(view -> view.projectId() == projectId
                    && view.userId() == userId && view.scope().equals(scope)
                    && view.name().equals(name)
                    && (excludeId == null || view.id() != excludeId));
        }

        @Override
        public void clearDefault(long projectId, long userId, String scope, long keepId) {
            rows.replaceAll((id, view) -> view.projectId() == projectId && view.userId() == userId
                    && view.scope().equals(scope) && view.id() != keepId && view.isDefault()
                    ? new SavedView(view.id(), view.projectId(), view.userId(), view.name(),
                    view.scope(), view.payload(), false, view.createdAt(), view.updatedAt())
                    : view);
        }

        int defaultCount(long userId, String scope) {
            return (int) rows.values().stream().filter(view -> view.userId() == userId
                    && view.scope().equals(scope) && view.isDefault()).count();
        }
    }
}
