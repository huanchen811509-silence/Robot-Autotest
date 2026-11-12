package com.qa.autotest.application.execution.monitor;

import com.qa.autotest.application.execution.RunCompletionListener;
import com.qa.autotest.domain.execution.RunRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 监视器收尾钩子（W53-01）：run 进终态后把命令集合落盘并释放监视器。
 *
 * <p>挂在 {@link RunCompletionListener} 上而不是改执行服务，原因与
 * {@code RunNotificationListener} 相同：监听器在事务提交后执行，异常由 {@code RunService}
 * 吞掉，<b>不可能改变 run 结论</b>。如果直接写进执行链的 finally，
 * 一次落盘失败就有机会把「跑完了」变成「异常」——那是监视器破坏了被监视对象。
 *
 * <p>为什么要收尾而不是等 GC：命令集合要写进 {@code commands.jsonl} 才算「可查、可 diff」；
 * 只留在内存里的命令集合在进程重启后就没了，等于没采集。
 */
@Service
public class MonitorFinalizeListener implements RunCompletionListener {

    private static final Logger log = LoggerFactory.getLogger(MonitorFinalizeListener.class);

    private final MonitorRegistry monitors;

    public MonitorFinalizeListener(MonitorRegistry monitors) {
        this.monitors = monitors;
    }

    @Override
    public void onCompleted(RunRecord run) {
        if (run == null || run.runId() == null) {
            return;
        }
        try {
            int count = monitors.finish(run.runId());
            log.debug("监视器收尾完成 run={} 命令数={}", run.runId(), count);
        } catch (RuntimeException e) {
            // 双保险：MonitorRegistry.finish 内部已吞异常，这里再兜一层
            log.debug("监视器收尾失败 run={}：{}", run.runId(), e.toString());
        }
    }
}
