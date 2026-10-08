package grit.stockIt.domain.matching.lock;

import grit.stockIt.domain.matching.entity.ConsumerWatermark;
import grit.stockIt.domain.matching.repository.ConsumerWatermarkRepository;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("StockMatchingLock — 종목 락과 워커 위치를 한 왕복에")
class StockMatchingLockAcquireAndAdvanceTest extends IntegrationTestSupport {

    private static final String TOPIC = "matching.commands";
    private static final String STOCK = "005930";

    @Autowired
    private StockMatchingLock stockMatchingLock;

    @Autowired
    private ConsumerWatermarkRepository consumerWatermarkRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("처음 보는 파티션은 위치를 만들고 true")
    void newPartition_advances() {
        assertThat(acquireAndAdvance(3, 0L)).isTrue();
        assertThat(lastOffsetOf(3)).isEqualTo(0L);
    }

    @Test
    @DisplayName("더 큰 offset 만 전진한다 — 같거나 작은 offset(재전달)은 false 이고 위치는 그대로")
    void onlyForward() {
        acquireAndAdvance(0, 10L);

        assertThat(acquireAndAdvance(0, 10L)).as("같은 offset = 재전달").isFalse();
        assertThat(acquireAndAdvance(0, 9L)).as("더 작은 offset").isFalse();
        assertThat(lastOffsetOf(0)).isEqualTo(10L);

        assertThat(acquireAndAdvance(0, 11L)).isTrue();
        assertThat(lastOffsetOf(0)).isEqualTo(11L);
    }

    @Test
    @DisplayName("파티션마다 따로 기록한다")
    void perPartition() {
        acquireAndAdvance(0, 100L);

        assertThat(acquireAndAdvance(1, 5L)).isTrue();
        assertThat(lastOffsetOf(0)).isEqualTo(100L);
        assertThat(lastOffsetOf(1)).isEqualTo(5L);
    }

    @Test
    @DisplayName("락 타임아웃이 락보다 먼저 걸린다 — 다른 트랜잭션이 종목 락을 쥐고 있으면 무한히 기다리지 않고 실패하고, 위치도 남지 않는다")
    void lockTimeoutAppliesBeforeLock() throws InterruptedException {
        String original = (String) ReflectionTestUtils.getField(stockMatchingLock, "defaultTimeout");
        ReflectionTestUtils.setField(stockMatchingLock, "defaultTimeout", "300ms");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> transactionTemplate.executeWithoutResult(status -> {
            stockMatchingLock.acquire(STOCK, "5s");
            holding.countDown();
            await(release);
        }));
        try {
            holder.start();
            assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            assertThatThrownBy(() -> acquireAndAdvance(7, 1L)).isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
        } finally {
            release.countDown();
            holder.join(5_000);
            ReflectionTestUtils.setField(stockMatchingLock, "defaultTimeout", original);
        }
        assertThat(consumerWatermarkRepository.findAll()).as("락을 못 잡았으면 위치도 롤백").isEmpty();
    }

    private boolean acquireAndAdvance(int partition, long offset) {
        Boolean result = transactionTemplate.execute(status ->
                stockMatchingLock.acquireAndAdvance(STOCK, TOPIC, partition, offset));
        return Boolean.TRUE.equals(result);
    }

    private long lastOffsetOf(int partition) {
        return consumerWatermarkRepository.findAll().stream()
                .filter(row -> row.getId().getTopic().equals(TOPIC) && row.getId().getPartitionNo() == partition)
                .mapToLong(ConsumerWatermark::getLastOffset)
                .findFirst()
                .orElseThrow();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
