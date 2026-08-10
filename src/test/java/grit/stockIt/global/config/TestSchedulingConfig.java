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

/**
 * 테스트 환경에서 스케줄러를 비활성화하는 설정 클래스
 * application-test.yml의 spring.task.scheduling.enabled=false와 함께 사용
 *
 * 근본 교정(2026-08): 과거 taskRegistrar.setScheduler(null)은 ScheduledTaskRegistrar.getScheduler()가
 * null로 남아, Spring이 (WebSocketConfig 등이 제공하는) 다른 TaskScheduler @Bean으로 폴백하거나
 * 자체 ThreadPoolTaskScheduler를 새로 만들어 백그라운드 @Scheduled(RankingService.updateAllRankings 등)이
 * 테스트 컨텍스트 안에서 실제로 발화하게 만들었다 — 특성화 테스트의 결정성(발화 0)을 깨는 원인.
 * getScheduler()를 non-null로 유지하면서도 아무 것도 실행하지 않는 no-op TaskScheduler를 주입해
 * 폴백과 자체 재생성을 동시에 억제한다.
 */
@Configuration
@ConditionalOnProperty(name = "spring.task.scheduling.enabled", havingValue = "false", matchIfMissing = false)
public class TestSchedulingConfig implements SchedulingConfigurer {

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.setScheduler(noOpTaskScheduler());
    }

    @Bean
    public TaskScheduler noOpTaskScheduler() {
        return new NoOpTaskScheduler();
    }

    /**
     * 어떤 Runnable도 실제로 실행하지 않는 TaskScheduler.
     * TaskScheduler의 모든 추상 오버로드(Trigger/Instant/Duration 기반)를 구현하며,
     * 항상 non-null 완료 더미 ScheduledFuture를 반환한다 — 컨텍스트 종료 시
     * ScheduledTaskRegistrar가 등록된 future.cancel()을 호출해도 NPE가 발생하지 않는다.
     */
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

    /**
     * 항상 완료·취소 상태로 취급되는 더미 ScheduledFuture.
     * cancel/get 호출이 어떤 상태에서 호출되어도 예외 없이 무해하게 처리된다.
     */
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
