package com.smwu.backend.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String GENERATION_EXECUTOR = "generationExecutor";
    public static final String EXTRACTION_EXECUTOR = "extractionExecutor";

    /** 문제 생성용 스레드 풀. 문항 1개 = 작업 1개, LLM 동시 호출 4개로 제한 */
    @Bean(name = GENERATION_EXECUTOR)
    public TaskExecutor generationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("generation-");
        executor.initialize();
        return executor;
    }

    /** 기출 PDF 추출용 스레드 풀. 시험지 1개 추출에 1~2분 걸리므로 동시 2개로 제한 */
    @Bean(name = EXTRACTION_EXECUTOR)
    public TaskExecutor extractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("extraction-");
        executor.initialize();
        return executor;
    }
}
