package com.qa.autotest.infrastructure;

import com.qa.autotest.common.async.AsyncProperties;
import com.qa.autotest.common.async.BoundedExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审计等后台写入共用一个有界执行器（EW32-04）。
 *
 * <p>{@code destroyMethod="close"} 让 Spring 停机时先排空队列再退出；配合
 * {@code server.shutdown=graceful} 与 {@code spring.lifecycle.timeout-per-shutdown-phase}，
 * 保证「停机不丢审计」。
 */
@Configuration
public class AsyncExecutorConfig {

    @Bean(destroyMethod = "close")
    public BoundedExecutor auditExecutor(AsyncProperties properties) {
        return new BoundedExecutor(properties);
    }
}
