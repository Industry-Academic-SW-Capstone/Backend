package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.entity.ConsumerWatermark;
import grit.stockIt.domain.matching.queue.CommandPosition;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsumerWatermarkWriter — 워커 위치를 앞으로만 기록한다")
class ConsumerWatermarkWriterTest extends IntegrationTestSupport {

    @Autowired
    private ConsumerWatermarkWriter consumerWatermarkWriter;

    @Autowired
    private ConsumerWatermarkRepository consumerWatermarkRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    // 워터마크는 테스트 사이에 지우지 않는다. 테스트마다 다른 토픽 이름을 써서 서로의 위치에 걸리지 않게 한다.
    private String topic;

    @BeforeEach
    void setUp() {
        topic = "matching.commands-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("처음 보는 파티션은 위치를 만들고 true")
    void newPartition_advances() {
        assertThat(advance(3, 0L)).isTrue();
        assertThat(lastOffsetOf(3)).isEqualTo(0L);
    }

    @Test
    @DisplayName("더 큰 offset 만 전진한다 — 같거나 작은 offset(재전달)은 false 이고 위치는 그대로")
    void onlyForward() {
        advance(0, 10L);

        assertThat(advance(0, 10L)).as("같은 offset = 재전달").isFalse();
        assertThat(advance(0, 9L)).as("더 작은 offset").isFalse();
        assertThat(lastOffsetOf(0)).isEqualTo(10L);

        assertThat(advance(0, 11L)).isTrue();
        assertThat(lastOffsetOf(0)).isEqualTo(11L);
    }

    @Test
    @DisplayName("파티션마다 따로 기록한다")
    void perPartition() {
        advance(0, 100L);

        assertThat(advance(1, 5L)).isTrue();
        assertThat(lastOffsetOf(0)).isEqualTo(100L);
        assertThat(lastOffsetOf(1)).isEqualTo(5L);
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 위치도 남지 않는다 — 명령 처리가 실패하면 다시 시도할 수 있다")
    void rolledBack_notRecorded() {
        transactionTemplate.executeWithoutResult(status -> {
            consumerWatermarkWriter.advance(new CommandPosition(topic, 0, 1L));
            status.setRollbackOnly();
        });

        assertThat(advance(0, 1L)).isTrue();
    }

    private boolean advance(int partition, long offset) {
        Boolean result = transactionTemplate.execute(status ->
                consumerWatermarkWriter.advance(new CommandPosition(topic, partition, offset)));
        return Boolean.TRUE.equals(result);
    }

    private long lastOffsetOf(int partition) {
        return consumerWatermarkRepository.findAll().stream()
                .filter(row -> row.getId().getTopic().equals(topic) && row.getId().getPartitionNo() == partition)
                .mapToLong(ConsumerWatermark::getLastOffset)
                .findFirst()
                .orElseThrow();
    }
}
