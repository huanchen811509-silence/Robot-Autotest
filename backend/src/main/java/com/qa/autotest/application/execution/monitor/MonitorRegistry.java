package com.qa.autotest.application.execution.monitor;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 监视器注册表（W53-01 的接入层）：一条 run 一个监视器，按内核隔离。
 *
 * <p><b>为什么要有这一层，而不是让执行器直接 new 一个监视器</b>：
 * 执行器（{@code PabotOrchestrator} / {@code PytestRunAdapter}）只该关心「把这一行日志
 * 交给监视器」，不该关心监视器是否存在、是否开启、创建失败怎么办。把这三种情况收在
 * 注册表里，执行器那边永远只有一行调用，也就永远不可能因为监视器而失败（R4）。
 *
 * <p><b>「监视器停掉后执行照常完成」怎么保证</b>：{@link #feed} 与 {@link #finish}
 * 全路径 try/catch，任何异常只写 debug 日志。{@code execution.monitor-enabled=false}
 * 时 {@link #feed} 直接返回——这是给「怀疑监视器拖慢执行」时的开关，
 * 关掉后执行链一行代码都不用改。
 *
 * <p>内存：监视器实例在 {@link #finish} 后立即从 map 移除，快照只留命令集合（已落盘）。
 * 不缓存快照是有意的——它可能含 5000 行日志，留在堆里等 GC 是浪费；
 * 要看历史监视数据请读证据文件。
 */
@Component
public class MonitorRegistry {

    private static final Logger log = LoggerFactory.getLogger(MonitorRegistry.class);

    private final Map<String, ExecutionMonitor> monitors = new ConcurrentHashMap<>();
    private final CommandSetService commandSet;
    private final Clock clock;
    private final boolean enabled;

    public MonitorRegistry(CommandSetService commandSet, Clock clock,
                           @Value("${execution.monitor-enabled:true}") boolean enabled) {
        this.commandSet = commandSet;
        this.clock = clock;
        this.enabled = enabled;
    }

    /** 取（或建）某 run 某内核的监视器。创建失败返回 null，调用方无需判空处理。 */
    public ExecutionMonitor of(String runId, String kernel) {
        if (!enabled || runId == null || runId.isBlank()) {
            return null;
        }
        try {
            return monitors.computeIfAbsent(key(runId, kernel),
                    ignored -> ExecutionMonitor.forKernel(runId, kernel, clock));
        } catch (RuntimeException e) {
            log.debug("监视器创建失败 run={} kernel={}：{}", runId, kernel, e.toString());
            return null;
        }
    }

    /**
     * 喂一行日志。这是执行器唯一的调用入口——它永不抛异常。
     *
     * @param kernel {@code robot} / {@code pytest}
     */
    public void feed(String runId, String kernel, String line) {
        ExecutionMonitor monitor = of(runId, kernel);
        if (monitor == null || line == null) {
            return;
        }
        try {
            monitor.stdout(line);
        } catch (RuntimeException e) {
            log.debug("监视器吞行失败 run={}：{}", runId, e.toString());
        }
    }

    /** 喂一行标准错误。 */
    public void feedError(String runId, String kernel, String line) {
        ExecutionMonitor monitor = of(runId, kernel);
        if (monitor == null || line == null) {
            return;
        }
        try {
            monitor.stderr(line);
        } catch (RuntimeException e) {
            log.debug("监视器吞错误行失败 run={}：{}", runId, e.toString());
        }
    }

    /** 平台事件（租约、调度、超时）也进同一条事件流。 */
    public void event(String runId, String kernel, String type, String detail) {
        ExecutionMonitor monitor = of(runId, kernel);
        if (monitor == null) {
            return;
        }
        try {
            monitor.executionEvent(type, detail);
        } catch (RuntimeException e) {
            log.debug("监视器吞事件失败 run={}：{}", runId, e.toString());
        }
    }

    /** 这条 run 当前是否处于「有监视数据」状态（页面据此显示「监视不可用」）。 */
    public boolean monitored(String runId) {
        for (String key : monitors.keySet()) {
            if (key.startsWith(runId + "|")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 收尾：把命令集合落盘，然后丢弃监视器。
     *
     * <p>落盘失败不影响执行结果（已在 {@link CommandSetService#write} 内部吞掉）。
     * 返回本次落盘的命令条数，供调用方记日志；没有监视器时返回 0。
     */
    public int finish(String runId) {
        int total = 0;
        for (String key : new ArrayList<>(monitors.keySet())) {
            if (!key.startsWith(runId + "|")) {
                continue;
            }
            ExecutionMonitor monitor = monitors.remove(key);
            if (monitor == null) {
                continue;
            }
            try {
                ExecutionMonitor.Snapshot snapshot = monitor.snapshot();
                commandSet.write(runId, snapshot.commands());
                total += snapshot.commands().size();
                if (snapshot.degraded() || snapshot.totalDropped() > 0) {
                    // 显式记录，避免「日志看起来完整但其实丢了一半」
                    log.info("监视数据不完整 run={} 丢弃行={} 丢弃事件={} 原因={}",
                            runId, snapshot.totalDropped(), snapshot.droppedEvents(),
                            snapshot.degradedReasons());
                }
            } catch (RuntimeException e) {
                log.debug("监视器收尾失败 run={}：{}", runId, e.toString());
            }
        }
        return total;
    }

    /** 某 run 的命令集合（收尾后从文件读；未收尾时从内存取）。 */
    public List<CommandRecord> commands(String runId) {
        for (Map.Entry<String, ExecutionMonitor> entry : monitors.entrySet()) {
            if (entry.getKey().startsWith(runId + "|")) {
                return entry.getValue().snapshot().commands();
            }
        }
        return commandSet.read(runId);
    }

    private static String key(String runId, String kernel) {
        String value = kernel == null || kernel.isBlank() ? "other"
                : kernel.trim().toLowerCase(java.util.Locale.ROOT);
        return runId + "|" + value;
    }
}
