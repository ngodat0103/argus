package dev.datrollout.argus;

import com.google.common.util.concurrent.MoreExecutors;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration(proxyBeanMethods = false)
@Slf4j
public class ThreadConfiguration {

    public static final String VIRTUAL_THREAD = "v-ioThread-";
    public static final String WORKER_THREAD = "p-workerThread-";
    public static final String SCHEDULED_THREAD = "s-scheduledThread-";

    @Bean(name = {TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME})
    @Primary
    ExecutorService applicationTaskExecutor() {
        return MoreExecutors.newDirectExecutorService();
    }

    @Bean(VIRTUAL_THREAD)
    ExecutorService virtualThreadExecutorService() {
        ThreadFactory threadFactory = Thread.ofVirtual().name(VIRTUAL_THREAD, 0).factory();
        return Executors.newThreadPerTaskExecutor(threadFactory);
    }

    @Bean(WORKER_THREAD)
    ExecutorService workerExecutorService() {
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int inferredThreads = Math.max(1, availableProcessors - 1);
        ThreadPoolExecutor threadPoolExecutor = new ThreadPoolExecutor(
                inferredThreads,
                inferredThreads,
                60,
                TimeUnit.SECONDS,
                new java.util.concurrent.PriorityBlockingQueue<>(1000));
        threadPoolExecutor.allowCoreThreadTimeOut(true);
        threadPoolExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ThreadFactory threadFactory = Thread.ofPlatform()
                .name(WORKER_THREAD, 0)
                .priority(Thread.MIN_PRIORITY)
                .factory();
        threadPoolExecutor.setThreadFactory(threadFactory);
        threadPoolExecutor.prestartAllCoreThreads();
        threadPoolExecutor.execute(() -> log.info("Worker thread pool started"));
        return threadPoolExecutor;
    }

    @Bean(SCHEDULED_THREAD)
    @Primary
    ScheduledThreadPoolExecutor scheduledThreadPoolExecutor() {
        ThreadFactory threadFactory = Thread.ofPlatform().name(SCHEDULED_THREAD).factory();
        return new ScheduledThreadPoolExecutor(1, threadFactory);
    }
}
