package com.qa.autotest.application.node;

import com.qa.autotest.application.iam.AccessControlService;
import com.qa.autotest.application.iam.AuditService;
import com.qa.autotest.application.repository.ExecutionNodeRepository;
import com.qa.autotest.common.BusinessException;
import com.qa.autotest.common.ErrorCode;
import com.qa.autotest.common.tenant.TenantContext;
import com.qa.autotest.domain.node.ExecutionNode;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v5.33.0 / FR-v5.33-02：节点编辑 / 轮换令牌 / 归档 / 恢复。
 *
 * <p>三条硬约束在这里钉住：① 在线节点不能删（删了它还会心跳，看着像「删了又活了」）；
 * ② 有在跑任务的节点不能删；③ 归档是软删（run.node_id 还指着它，硬删会断历史）。
 */
class ExecutionNodeCrudTest {

    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    private final Map<Long, ExecutionNode> nodes = new HashMap<>();
    private final Set<Long> archived = new HashSet<>();
    private final Map<Long, Integer> running = new HashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final List<String> auditActions = new ArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.set(1L, "admin", null);
        nodes.clear();
        archived.clear();
        running.clear();
        seq.set(0);
        auditActions.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("编辑：只改名称/并发/地址，状态与令牌不动；改名撞车 409")
    void updateProfile() {
        ExecutionNodeService service = service();
        long id = seed("node-a", ExecutionNode.ONLINE, "hash-a");
        seed("node-b", ExecutionNode.NEW, "hash-b");

        ExecutionNodeService.NodeView view = service.updateProfile(id, "node-a2", 8, null);

        assertEquals("node-a2", view.name());
        assertEquals(8, view.maxConcurrency());
        assertEquals(ExecutionNode.ONLINE, view.status(), "改名不该让在线节点掉线");
        assertEquals("hash-a", nodes.get(id).tokenHash(), "编辑不得碰令牌");

        BusinessException dup = assertThrows(BusinessException.class,
                () -> service.updateProfile(id, "node-b", null, null));
        assertEquals(ErrorCode.CONFLICT, dup.errorCode());
    }

    @Test
    @DisplayName("编辑：并发上限越界 400，改回自己原名字不算冲突")
    void updateProfileValidation() {
        ExecutionNodeService service = service();
        long id = seed("node-a", ExecutionNode.NEW, "hash-a");

        assertEquals("node-a", service.updateProfile(id, "node-a", 2, null).name());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateProfile(id, "node-a", 65, null));
        assertEquals(ErrorCode.INVALID_ARGUMENT, ex.errorCode());
    }

    @Test
    @DisplayName("轮换令牌：哈希变化、明文只返回一次、审计不记明文")
    void rotateToken() {
        ExecutionNodeService service = service();
        long id = seed("node-a", ExecutionNode.ONLINE, "old-hash");

        ExecutionNodeService.RegisterResult result = service.rotateToken(id);

        assertEquals(64, result.token().length(), "32 字节 hex");
        assertNotEquals("old-hash", nodes.get(id).tokenHash());
        assertEquals(ExecutionNodeService.sha256(result.token()), nodes.get(id).tokenHash(),
                "存的是明文摘要，明文不落库");
        assertTrue(auditActions.contains("NODE_TOKEN_ROTATE"));
    }

    @Test
    @DisplayName("归档：在线节点与有在跑任务的节点都拒删，离线空闲节点可删")
    void archiveGuards() {
        ExecutionNodeService service = service();
        long online = seed("node-online", ExecutionNode.ONLINE, "h1");
        long busy = seed("node-busy", ExecutionNode.OFFLINE, "h2");
        long idle = seed("node-idle", ExecutionNode.OFFLINE, "h3");
        running.put(busy, 2);

        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BusinessException.class,
                () -> service.archive(online)).errorCode());
        BusinessException busyEx = assertThrows(BusinessException.class,
                () -> service.archive(busy));
        assertTrue(busyEx.getMessage().contains("2 个运行中"), busyEx.getMessage());

        service.archive(idle);
        assertTrue(archived.contains(idle));
        assertTrue(nodes.containsKey(idle), "软删：行还在，历史 run 不断链");
    }

    @Test
    @DisplayName("归档后默认列表不含它，「含归档」视图可见并标记 archived")
    void archiveHidesFromDefaultList() {
        ExecutionNodeService service = service();
        long keep = seed("node-keep", ExecutionNode.NEW, "h1");
        long gone = seed("node-gone", ExecutionNode.OFFLINE, "h2");
        service.archive(gone);

        List<ExecutionNodeService.NodeView> visible = service.list(false);
        assertEquals(List.of(keep), visible.stream().map(ExecutionNodeService.NodeView::id).toList());
        assertTrue(!visible.get(0).archived());

        List<ExecutionNodeService.NodeView> all = service.list(true);
        assertEquals(2, all.size());
        assertTrue(all.stream().filter(v -> v.id() == gone).findFirst().orElseThrow().archived());
    }

    @Test
    @DisplayName("恢复：回到 NEW 且清掉归档标记；未归档节点恢复报 400")
    void restore() {
        ExecutionNodeService service = service();
        long id = seed("node-a", ExecutionNode.OFFLINE, "h1");
        service.archive(id);

        ExecutionNodeService.NodeView view = service.restore(id);

        assertEquals(ExecutionNode.NEW, view.status(), "恢复后要重新证明存活");
        assertTrue(!view.archived());
        assertEquals(ErrorCode.INVALID_ARGUMENT, assertThrows(BusinessException.class,
                () -> service.restore(id)).errorCode());
    }

    @Test
    @DisplayName("归档幂等：重复删不报错、不重复写审计")
    void archiveIdempotent() {
        ExecutionNodeService service = service();
        long id = seed("node-a", ExecutionNode.OFFLINE, "h1");
        service.archive(id);
        long before = auditActions.stream().filter("NODE_ARCHIVE"::equals).count();
        service.archive(id);
        assertEquals(before, auditActions.stream().filter("NODE_ARCHIVE"::equals).count());
    }

    // ---------------------------------------------------------------- 夹具

    private long seed(String name, String status, String hash) {
        long id = seq.incrementAndGet();
        nodes.put(id, new ExecutionNode(id, name, hash, status, true, false, 4,
                NOW, Instant.EPOCH, null));
        return id;
    }

    private ExecutionNodeService service() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AccessControlService access = org.mockito.Mockito.mock(AccessControlService.class);
        org.mockito.Mockito.when(access.isAdmin()).thenReturn(true);
        // AuditService 是具体类（不是接口），Proxy 用不了；用 Mockito 捕获 action 名
        AuditService audit = org.mockito.Mockito.mock(AuditService.class);
        org.mockito.Mockito.doAnswer(inv -> {
            // 必须显式 (Object)：String.valueOf 有 char[] 重载，泛型会被推断成 char[] 并运行时炸
            auditActions.add(String.valueOf((Object) inv.getArgument(0)));
            return null;
        }).when(audit).record(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        return new ExecutionNodeService(repo(), access, audit, 30, 0, clock);
    }

    private ExecutionNodeRepository repo() {
        return (ExecutionNodeRepository) Proxy.newProxyInstance(
                ExecutionNodeRepository.class.getClassLoader(),
                new Class<?>[]{ExecutionNodeRepository.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "find" -> Optional.ofNullable(nodes.get((Long) args[0]));
                    case "findByName" -> nodes.values().stream()
                            .filter(n -> n.name().equals(args[0])).findFirst();
                    case "nameExists" -> nodes.values().stream()
                            .anyMatch(n -> n.name().equals(args[0]));
                    case "list" -> nodes.values().stream()
                            .filter(n -> args == null || args.length == 0
                                    || Boolean.TRUE.equals(args[0])
                                    || !archived.contains(n.id()))
                            .sorted(java.util.Comparator.comparingLong(ExecutionNode::id)).toList();
                    case "archivedIds" -> Set.copyOf(archived);
                    case "runningRunsByNode" -> Map.copyOf(running);
                    case "latestRunsByNode" -> Map.of();
                    case "updateProfile" -> {
                        long id = (Long) args[0];
                        ExecutionNode old = nodes.get(id);
                        nodes.put(id, new ExecutionNode(old.id(), (String) args[1], old.tokenHash(),
                                old.status(), old.rfAvailable(), old.pytestAvailable(),
                                (Integer) args[2], old.lastHeartbeatAt(), old.registeredAt(),
                                (String) args[3]));
                        yield null;
                    }
                    case "updateTokenHash" -> {
                        long id = (Long) args[0];
                        ExecutionNode old = nodes.get(id);
                        nodes.put(id, new ExecutionNode(old.id(), old.name(), (String) args[1],
                                old.status(), old.rfAvailable(), old.pytestAvailable(),
                                old.maxConcurrency(), old.lastHeartbeatAt(), old.registeredAt(),
                                old.agentBaseUrl()));
                        yield null;
                    }
                    case "archive" -> {
                        long id = (Long) args[0];
                        archived.add(id);
                        ExecutionNode old = nodes.get(id);
                        nodes.put(id, new ExecutionNode(old.id(), old.name(), old.tokenHash(),
                                ExecutionNode.OFFLINE, old.rfAvailable(), old.pytestAvailable(),
                                old.maxConcurrency(), old.lastHeartbeatAt(), old.registeredAt(),
                                old.agentBaseUrl()));
                        yield null;
                    }
                    case "restore" -> {
                        long id = (Long) args[0];
                        archived.remove(id);
                        ExecutionNode old = nodes.get(id);
                        nodes.put(id, new ExecutionNode(old.id(), old.name(), old.tokenHash(),
                                ExecutionNode.NEW, old.rfAvailable(), old.pytestAvailable(),
                                old.maxConcurrency(), old.lastHeartbeatAt(), old.registeredAt(),
                                old.agentBaseUrl()));
                        yield null;
                    }
                    case "updateStatus" -> null;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        return null;
    }
}
