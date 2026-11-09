package com.qa.autotest.application.execution;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 运行中的子进程登记表：取消与超时监控据此按 runId 找到要杀的进程树。 */
@Component
public class ProcessRegistry {

    /** 同一 run 的进程列表 + 首次登记时间（孤儿清扫据此避开刚启动的运行）。 */
    private record Entry(CopyOnWriteArrayList<Process> processes, Instant registeredAt) {
        Entry {
            if (processes == null) {
                processes = new CopyOnWriteArrayList<>();
            }
        }
    }

    private final Map<String, Entry> byRun = new ConcurrentHashMap<>();
    private final Clock clock;

    /** 单测与无 Spring 场景：UTC 系统时钟。 */
    public ProcessRegistry() {
        this(Clock.systemUTC());
    }

    @Autowired
    public ProcessRegistry(Clock executionClock) {
        this.clock = executionClock == null ? Clock.systemUTC() : executionClock;
    }

    public void register(String runId, Process process) {
        if (process == null) {
            return;
        }
        byRun.computeIfAbsent(runId,
                key -> new Entry(new CopyOnWriteArrayList<>(), clock.instant()))
                .processes().add(process);
    }

    public void unregister(String runId) {
        byRun.remove(runId);
    }

    public List<Process> processes(String runId) {
        Entry entry = byRun.get(runId);
        return entry == null ? List.of() : List.copyOf(entry.processes());
    }

    /** 首次登记时间；未登记返回空。 */
    public Optional<Instant> registeredAt(String runId) {
        Entry entry = byRun.get(runId);
        return entry == null ? Optional.empty() : Optional.of(entry.registeredAt());
    }

    public Set<String> activeRunIds() {
        return Set.copyOf(byRun.keySet());
    }
}
