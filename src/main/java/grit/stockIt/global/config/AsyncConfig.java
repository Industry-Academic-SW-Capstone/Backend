package grit.stockIt.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig implements AsyncConfigurer {

    public static final String MISSION_EXECUTOR = "missionExecutor";
    public static final String NOTIFICATION_EXECUTOR = "notificationExecutor";

    @Bean(MISSION_EXECUTOR)
    public ThreadPoolTaskExecutor missionExecutor(@Value("${app.async.mission.pool-size:2}") int poolSize) {
        return fixedSizeExecutor("mission-", poolSize);
    }

    @Bean(NOTIFICATION_EXECUTOR)
    public ThreadPoolTaskExecutor notificationExecutor(@Value("${app.async.notification.pool-size:2}") int poolSize) {
        return fixedSizeExecutor("notification-", poolSize);
    }

    @Bean
    public ThreadPoolTaskExecutor defaultAsyncExecutor() {
        return fixedSizeExecutor("async-", 2);
    }

    @Override
    public Executor getAsyncExecutor() {
        return defaultAsyncExecutor();
    }

    // 대기열은 무제한(기본값)이라 풀 크기를 넘는 작업은 거절되지 않고 줄을 선다.
    private static ThreadPoolTaskExecutor fixedSizeExecutor(String threadNamePrefix, int poolSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
