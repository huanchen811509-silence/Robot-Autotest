package com.qa.autotest.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.repository.NotificationRepository;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.common.PageResult;
import com.qa.autotest.domain.execution.RunRecord;
import com.qa.autotest.domain.execution.RunState;
import com.qa.autotest.domain.notification.NotificationItem;
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

/** 通知中心：终态写入去重、偏好开关、已读按用户隔离。 */
class NotificationServiceTest {

    private static final long PROJECT = 1L;
    private static final long ALICE = 7L;
    private static final long BOB = 8L;

    private FakeNotificationRepository repository;
    private NotificationService service;

    @BeforeEach
    void setUp() {
        repository = new FakeNotificationRepository();
        AccessControlService access = mock(AccessControlService.class);
        doNothing().when(access).bindProject(anyLong());
        TenantContext.clear();
        service = new NotificationService(repository, access);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("非终态运行不写通知")
    void 非终态忽略() {
        service.notifyRun(run(RunState.RUNNING, 0, 0));

        assertThat(repository.rowCount()).isZero();
    }

    @Test
    @DisplayName("同一 run_key+type 重复回调只更新不新增")
    void 终态去重() {
        service.notifyRun(run(RunState.FINISHED, 9, 1));
        service.notifyRun(run(RunState.FINISHED, 9, 1));

        assertThat(repository.rowCount()).isEqualTo(1);
        assertThat(repository.first().type()).isEqualTo("RUN_FINISHED");
        assertThat(repository.first().message()).contains("通过 9");
    }

    @Test
    @DisplayName("失败/取消归入 RUN_FAILED")
    void 失败类型() {
        service.notifyRun(run(RunState.CANCELED, 2, 1));

        assertThat(repository.first().type()).isEqualTo("RUN_FAILED");
        assertThat(repository.first().message()).contains("已取消");
    }

    @Test
    @DisplayName("用户关闭该类型时跳过写入")
    void 偏好关闭() {
        TenantContext.set(ALICE, "alice", PROJECT);
        repository.enable(ALICE, "RUN_FINISHED", false);

        service.notifyRun(run(RunState.FINISHED, 5, 0));

        assertThat(repository.rowCount()).isZero();
    }

    @Test
    @DisplayName("节点离线通知以 node:id 作为 run_key，重复判定仍只有一条")
    void 节点离线() {
        service.notifyNodeOffline(PROJECT, 3L, "node-3");
        service.notifyNodeOffline(PROJECT, 3L, "node-3");

        assertThat(repository.rowCount()).isEqualTo(1);
        assertThat(repository.first().runKey()).isEqualTo("node:3");
        assertThat(repository.first().message()).contains("node-3");
    }

    @Test
    @DisplayName("偏好读取补齐默认开启的四种类型")
    void 偏好默认开启() {
        TenantContext.set(ALICE, "alice", PROJECT);
        repository.enable(ALICE, "RUN_FAILED", false);

        List<NotificationRepository.Preference> preferences = service.preferences(PROJECT);

        assertThat(preferences).hasSize(4);
        assertThat(preferences).filteredOn(p -> p.type().equals("RUN_FAILED"))
                .singleElement().satisfies(p -> assertThat(p.enabled()).isFalse());
        assertThat(preferences).filteredOn(p -> p.type().equals("NODE_OFFLINE"))
                .singleElement().satisfies(p -> assertThat(p.enabled()).isTrue());
    }

    @Test
    @DisplayName("未读数按用户各算各的：通知本体共享，已读互不影响")
    void 未读按用户隔离() {
        service.notifyRun(run(RunState.FINISHED, 1, 0));

        TenantContext.set(ALICE, "alice", PROJECT);
        assertThat(service.unreadCount(PROJECT)).isEqualTo(1);
        service.markRead(PROJECT, 1L);

        // 同一个项目里 A 读完，B 仍然看到未读
        assertThat(service.unreadCount(PROJECT)).isZero();
        TenantContext.set(BOB, "bob", PROJECT);
        assertThat(service.unreadCount(PROJECT)).isEqualTo(1);
    }

    @Test
    @DisplayName("列表里 readAt 是当前用户自己的时间；B 看到 null")
    void 列表已读按用户() {
        service.notifyRun(run(RunState.FINISHED, 1, 0));
        TenantContext.set(ALICE, "alice", PROJECT);
        service.markRead(PROJECT, 1L);
        PageResult<NotificationItem> aliceAll = service.page(PROJECT, "all", 1, 20);

        assertThat(aliceAll.items()).singleElement()
                .satisfies(item -> assertThat(item.readAt()).isNotNull());

        TenantContext.set(BOB, "bob", PROJECT);
        PageResult<NotificationItem> bobAll = service.page(PROJECT, "all", 1, 20);

        assertThat(bobAll.items()).singleElement()
                .satisfies(item -> assertThat(item.readAt()).isNull());
    }

    @Test
    @DisplayName("重复标记已读幂等：第二次返回 0，且不推进时间")
    void 重复已读幂等() {
        service.notifyRun(run(RunState.FINISHED, 1, 0));
        TenantContext.set(ALICE, "alice", PROJECT);

        assertThat(service.markRead(PROJECT, 1L)).isEqualTo(1);
        Instant firstRead = repository.readAt(ALICE, 1L);
        assertThat(service.markRead(PROJECT, 1L)).isZero();
        assertThat(repository.readAt(ALICE, 1L)).isEqualTo(firstRead);
    }

    @Test
    @DisplayName("全部已读只补当前用户，返回新增条数，不影响他人")
    void 全部已读按用户() {
        service.notifyRun(run(RunState.FINISHED, 1, 0));
        service.notifyNodeOffline(PROJECT, 3L, "node-3");
        TenantContext.set(ALICE, "alice", PROJECT);

        assertThat(service.markAllRead(PROJECT)).isEqualTo(2);
        assertThat(service.markAllRead(PROJECT)).isZero();
        TenantContext.set(BOB, "bob", PROJECT);
        assertThat(service.unreadCount(PROJECT)).isEqualTo(2);
    }

    private static RunRecord run(RunState state, int passed, int failed) {
        return new RunRecord(1L, "RUN-1", PROJECT, null, state, 0, 1, 1,
                passed + failed, passed, failed, null, null, null, null, null, null, null, null);
    }

    /**
     * 内存假仓储：通知本体按 run_key+type 去重；已读按 (notification_id,user_id) 分开存，
     * 与 notification_read 的唯一键口径一致。
     */
    private static final class FakeNotificationRepository implements NotificationRepository {

        private final Map<String, Row> rows = new LinkedHashMap<>();
        private final Map<String, Instant> reads = new LinkedHashMap<>();
        private final Map<String, Boolean> preferences = new LinkedHashMap<>();
        private long nextId = 1;

        @Override
        public void upsert(long projectId, String runKey, String type, String status, String message,
                           Long operatorId) {
            rows.compute(runKey + "|" + type, (key, old) -> old == null
                    ? new Row(nextId++, projectId, runKey, type, status, message)
                    : new Row(old.id(), old.projectId(), old.runKey(), old.type(), status, message));
        }

        @Override
        public PageResult<NotificationItem> page(long projectId, long userId, boolean unreadOnly,
                                                 int page, int size) {
            List<NotificationItem> items = new ArrayList<>();
            for (Row row : rows.values()) {
                Instant readAt = reads.get(userId + "|" + row.id());
                if (unreadOnly && readAt != null) {
                    continue;
                }
                items.add(new NotificationItem(row.id(), row.projectId(), row.runKey(), row.type(),
                        row.status(), row.message(), readAt, Instant.now()));
            }
            return new PageResult<>(items, items.size(), Math.max(1, page), Math.max(1, size));
        }

        @Override
        public long unreadCount(long projectId, long userId) {
            return rows.values().stream()
                    .filter(row -> reads.get(userId + "|" + row.id()) == null)
                    .count();
        }

        @Override
        public int markRead(long projectId, long userId, long id) {
            if (rows.values().stream().noneMatch(row -> row.id() == id)) {
                return 0;
            }
            // putIfAbsent 对应 ON DUPLICATE KEY UPDATE read_at = read_at：重复不再改时间
            return reads.putIfAbsent(userId + "|" + id, Instant.now()) == null ? 1 : 0;
        }

        @Override
        public int markAllRead(long projectId, long userId) {
            int inserted = 0;
            for (Row row : rows.values()) {
                if (reads.putIfAbsent(userId + "|" + row.id(), Instant.now()) == null) {
                    inserted++;
                }
            }
            return inserted;
        }

        @Override
        public List<Preference> preferences(long projectId, long userId) {
            List<Preference> result = new ArrayList<>();
            preferences.forEach((type, enabled) -> result.add(new Preference(type, enabled)));
            return result;
        }

        @Override
        public void upsertPreference(long projectId, long userId, String type, boolean enabled,
                                     Long operatorId) {
            preferences.put(type, enabled);
        }

        @Override
        public Optional<Boolean> findPreference(long projectId, long userId, String type) {
            return Optional.ofNullable(preferences.get(type));
        }

        void enable(long userId, String type, boolean enabled) {
            preferences.put(type, enabled);
        }

        Instant readAt(long userId, long notificationId) {
            return reads.get(userId + "|" + notificationId);
        }

        int rowCount() {
            return rows.size();
        }

        Row first() {
            return rows.values().iterator().next();
        }

        private record Row(long id, long projectId, String runKey, String type, String status,
                           String message) {
        }
    }
}
