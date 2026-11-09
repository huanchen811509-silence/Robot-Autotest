package com.qa.autotest.application.execution;

import com.qa.autotest.application.notification.NotificationService;
import com.qa.autotest.domain.execution.RunRecord;
import org.springframework.stereotype.Service;

/**
 * 运行终态通知钩子（FR-3.1-08）。
 *
 * <p>放在 {@link RunCompletionListener} 上而不是直接改状态机：监听器在事务提交后执行，
 * 通知失败不会影响 run 结论；异常由 RunService 统一吞掉。
 */
@Service
public class RunNotificationListener implements RunCompletionListener {

    private final NotificationService notifications;

    public RunNotificationListener(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void onCompleted(RunRecord run) {
        notifications.notifyRun(run);
    }
}
