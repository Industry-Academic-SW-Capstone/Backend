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
 * {@code spring.task.scheduling.enabled=false}일 때 모든 {@code @Scheduled} 발화를 막는다.
 *
 * <p><b>어디서 쓰나</b>
 * <ul>
 *   <li>테스트 — {@code src/test/resources/application.yml}이 이 값을 false로 둔다.
 *       배경 스케줄러가 끼어들면 특성화 테스트의 결정성(발화 0)이 깨진다.</li>
 *   <li>벤치마크(staging 프로파일) — 주기 배치가 측정을 오염시킨다.
 *       {@code RankingService}는 보유 종목 현재가를 KIS 속도 제한(초당 25건)에 맞춰 순차 조회해
 *       60초 주기 톱니를 만들고, {@code RedisDBSyncService}는 미체결 주문마다
 *       {@code OrderBookStore#exists}를 호출한다. 후자가 특히 문제인데, 그 호출 비용이
 *       백엔드마다 다르다(Redis면 ZSCORE, JPA면 DB 조회). 즉 <b>RDB arm에만 주기적 DB 부하가
 *       더해져</b> 우리가 재려는 바로 그 차이를 편향시킨다.</li>
 * </ul>
 *
 * <p><b>왜 프로퍼티만으로는 부족한가.</b> {@code spring.task.scheduling.enabled}는 Spring Boot
 * 표준 프로퍼티가 아니다. {@code RankingService}가 {@code Environment}에서 직접 읽어 자체 차단할
 * 뿐이라, 그 코드가 없는 {@code RedisDBSyncService} 같은 스케줄러는 그대로 돈다.
 * 전부 멈추려면 스케줄러 자체를 무력화해야 한다.
 *
 * <p><b>왜 no-op {@link TaskScheduler}를 주입하나.</b> 과거 {@code taskRegistrar.setScheduler(null)}은
 * {@code ScheduledTaskRegistrar.getScheduler()}가 null로 남아, Spring이 다른 {@code TaskScheduler}
 * 빈(WebSocketConfig 등)으로 폴백하거나 자체 {@code ThreadPoolTaskScheduler}를 새로 만들어
 * 결국 {@code @Scheduled}가 발화했다. non-null이면서 아무 것도 실행하지 않는 스케줄러를 주입해
 * 폴백과 자체 재생성을 동시에 막는다.
 */
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
