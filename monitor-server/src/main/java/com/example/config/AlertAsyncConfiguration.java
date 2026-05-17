package com.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.concurrent.Executors;

/**
 * 告警评估异步执行器配置。
 * <p>
 * 启用 Spring {@code @Async} 支持，并提供基于 Java 21 虚拟线程的
 * {@code alertTaskExecutor} bean，以让 {@code AlertEvaluator.evaluate}
 * 在客户端上报链路之外异步执行，避免阻塞 InfluxDB 写入与 SSE 推送。
 */
@Configuration
@EnableAsync
public class AlertAsyncConfiguration {

    /**
     * 提供基于虚拟线程的异步执行器，供 {@code @Async("alertTaskExecutor")} 使用。
     * 每个评估任务一根虚拟线程，避免外部 I/O（数据库 / RabbitMQ）阻塞调用方。
     *
     * @return 异步任务执行器
     */
    @Bean(name = "alertTaskExecutor")
    public AsyncTaskExecutor alertTaskExecutor() {
        TaskExecutorAdapter adapter = new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
        adapter.setTaskDecorator(propagateRequestAttributes());
        return adapter;
    }

    /**
     * 将主线程上的 RequestAttributes 透传到虚拟线程，保持 MDC reqId 等上下文一致。
     *
     * @return TaskDecorator 实例
     */
    private TaskDecorator propagateRequestAttributes() {
        return runnable -> {
            RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
            return () -> {
                try {
                    if (attributes != null) {
                        RequestContextHolder.setRequestAttributes(attributes);
                    }
                    runnable.run();
                } finally {
                    RequestContextHolder.resetRequestAttributes();
                }
            };
        };
    }
}
