package grit.stockIt.global.config;

import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.domain.notification.service.ExecutionNotificationService;
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
        executor = asyncConfig.notificationExecutor(1);
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
        executor = asyncConfig.notificationExecutor(1);
        executor.initialize();
        AtomicReference<String> threadName = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        executor.execute(() -> {
            threadName.set(Thread.currentThread().getName());
            done.countDown();
        });

        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadName.get()).startsWith("notification-");
    }

    // 체결 경로의 미션·알림은 이제 Kafka 소비자(member.events)라 메모리 스레드 풀을 쓰지 않는다 — 재시작해도 대기열이 사라지지 않는다.
    @Test
    @DisplayName("체결 알림은 메모리 스레드 풀에서 돌지 않는다")
    void executionNotificationIsNotAsync() throws NoSuchMethodException {
        Async notification = ExecutionNotificationService.class
                .getMethod("handleExecutionFilledEvent", ExecutionFilledEvent.class)
                .getAnnotation(Async.class);

        assertThat(notification).isNull();
    }

    private void blockUntilReleased() {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
