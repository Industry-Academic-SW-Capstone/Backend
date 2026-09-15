package grit.stockIt.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
@Configuration
@ConditionalOnProperty(name = "spring.task.scheduling.enabled", havingValue = "false", matchIfMissing = false)
public class SchedulingDisabledConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(noOpTaskScheduler());
    }

    @Bean
    public TaskScheduler noOpTaskScheduler() {
        return new NoOpTaskScheduler();
    }

    // 어떤 Runnable도 실행하지 않는 TaskScheduler. 항상 non-null 더미 ScheduledFuture 를 반환해,
    // 컨텍스트 종료 시 ScheduledTaskRegistrar 가 future.cancel()을 호출해도 NPE 가 나지 않는다.
    private static final class NoOpTaskScheduler implements TaskScheduler {

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return NoOpScheduledFuture.INSTANCE;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return NoOpScheduledFuture.INSTANCE;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return NoOpScheduledFuture.INSTANCE;
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return NoOpScheduledFuture.INSTANCE;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return NoOpScheduledFuture.INSTANCE;
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return NoOpScheduledFuture.INSTANCE;
        }
    }

    // 항상 완료·취소 상태로 취급되는 더미 ScheduledFuture.
    private static final class NoOpScheduledFuture implements ScheduledFuture<Object> {

        static final NoOpScheduledFuture INSTANCE = new NoOpScheduledFuture();

        @Override
        public long getDelay(TimeUnit unit) {
            return 0L;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return true;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
