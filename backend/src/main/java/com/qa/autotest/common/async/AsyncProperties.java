package com.qa.autotest.common.async;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 异步执行配置（EW32-04）。默认 2/4 线程、1024 队列、停机最多等 10 秒。
 *
 * <p>容量按「审计峰值 5000 行/秒」估算：单条插入 ~2ms，2 线程约 1000 条/秒；
 * 队列 1024 可以吸收 1 秒突发，再满就同步降级，不会把请求打挂。
 */
@Component
@ConfigurationProperties(prefix = "app.async")
public class AsyncProperties {

    private int coreSize = 2;
    private int maxSize = 4;
    private int queueCapacity = 1024;
    private int keepAliveSeconds = 60;
    private int shutdownTimeoutSeconds = 10;

    public int getCoreSize() {
        return coreSize;
    }

    public void setCoreSize(int coreSize) {
        this.coreSize = coreSize;
    }

    public int getMaxSize() {
        return maxSize;
    }

    public void setMaxSize(int maxSize) {
        this.maxSize = maxSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(int keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public int getShutdownTimeoutSeconds() {
        return shutdownTimeoutSeconds;
    }

    public void setShutdownTimeoutSeconds(int shutdownTimeoutSeconds) {
        this.shutdownTimeoutSeconds = shutdownTimeoutSeconds;
    }
}
