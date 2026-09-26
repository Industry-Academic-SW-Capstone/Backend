package grit.stockIt.global.config;

import grit.stockIt.domain.mission.service.MissionEventListener;
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.domain.notification.service.ExecutionNotificationService;
import grit.stockIt.domain.order.event.TradeCompletionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    private final AsyncConfig asyncConfig = new AsyncConfig();
    private final CountDownLatch release = new CountDownLatch(1);
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void tearDown() {
        release.countDown();
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("풀 크기를 넘는 작업은 스레드를 더 만들지 않고 대기열에서 기다린다")
    void queuesInsteadOfCreatingThreads() {
        executor = asyncConfig.missionExecutor(1);
        executor.initialize();

        executor.execute(this::blockUntilReleased);
        executor.execute(this::blockUntilReleased);
        executor.execute(this::blockUntilReleased);

        assertThat(executor.getPoolSize()).isEqualTo(1);
        assertThat(executor.getQueueSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("작업은 풀 이름이 붙은 스레드에서 돈다")
    void runsOnNamedPoolThread() throws InterruptedException {
        executor = asyncConfig.missionExecutor(1);
        executor.initialize();
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("mission-");
    }

    // 체결마다 한 건씩 생기는 두 리스너다. 실행기를 지정하지 않으면 기본 실행기로 떨어진다.
    @Test
    @DisplayName("체결 경로의 비동기 리스너는 전용 실행기를 쓴다")
    void fillPathListenersUseDedicatedExecutors() throws NoSuchMethodException {
        Async mission = MissionEventListener.class
                .getMethod("handleTradeCompletionEvent", TradeCompletionEvent.class)
                .getAnnotation(Async.class);
        Async notification = ExecutionNotificationService.class
                .getMethod("handleExecutionFilledEvent", ExecutionFilledEvent.class)
                .getAnnotation(Async.class);

        assertThat(mission.value()).isEqualTo(AsyncConfig.MISSION_EXECUTOR);
        assertThat(notification.value()).isEqualTo(AsyncConfig.NOTIFICATION_EXECUTOR);
    }

    private void blockUntilReleased() {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
