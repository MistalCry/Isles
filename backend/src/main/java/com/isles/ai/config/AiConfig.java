package com.isles.ai.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 模块装配：Mapper 扫描、调度开关、流式回答线程池。
 *
 * <p>应用入口的 {@code @MapperScan} 只扫描既有领域包，AI/平台后台的 Mapper 在这里显式注册，
 * 避免修改公共启动类（目录归属约束）。{@code @EnableScheduling} 同样由本模块声明，
 * 频道助手自动任务才可运行（{@code app.ai.scheduler-enabled=false} 时任务体内部直接跳过）。
 */
@Configuration
@EnableScheduling
@MapperScan({"com.isles.ai.mapper", "com.isles.admin.mapper"})
public class AiConfig {

    /**
     * 助手被动接话线程池：有人回复助手评论时异步生成回复，不阻塞用户请求。
     */
    @Bean(name = "assistantReplyExecutor")
    public ThreadPoolTaskExecutor assistantReplyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("assistant-reply-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * SSE 流式回答执行线程池：回答是阻塞的 IO 调用，不能占用 Tomcat 请求线程。
     */
    @Bean(name = "aiStreamExecutor")
    public ThreadPoolTaskExecutor aiStreamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
