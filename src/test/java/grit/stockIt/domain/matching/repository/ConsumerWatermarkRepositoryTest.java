package grit.stockIt.domain.matching.repository;

import grit.stockIt.domain.matching.entity.ConsumerWatermark;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConsumerWatermarkRepository 조건부 전진")
class ConsumerWatermarkRepositoryTest extends IntegrationTestSupport {

    private static final String TOPIC = "matching.commands";

    @Autowired
    private ConsumerWatermarkRepository consumerWatermarkRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("처음 보는 파티션은 행을 만들고 1을 돌려준다")
    void advance_newPartition_inserts() {
        assertThat(advance(3, 0L)).isEqualTo(1);
        assertThat(lastOffsetOf(3)).isEqualTo(0L);
    }

    @Test
    @DisplayName("더 큰 offset 이면 올리고, 같거나 작은 offset 이면 그대로 두고 0을 돌려준다")
    void advance_onlyForward() {
        advance(0, 10L);

        assertThat(advance(0, 10L)).as("같은 offset = 재전달").isZero();
        assertThat(advance(0, 9L)).as("더 작은 offset").isZero();
        assertThat(lastOffsetOf(0)).isEqualTo(10L);

        assertThat(advance(0, 11L)).isEqualTo(1);
        assertThat(lastOffsetOf(0)).isEqualTo(11L);
    }

    @Test
    @DisplayName("파티션마다 따로 기록한다")
    void advance_perPartition() {
        advance(0, 100L);

        assertThat(advance(1, 5L)).isEqualTo(1);
        assertThat(lastOffsetOf(0)).isEqualTo(100L);
        assertThat(lastOffsetOf(1)).isEqualTo(5L);
    }

    private int advance(int partition, long offset) {
        Integer result = transactionTemplate.execute(status ->
                consumerWatermarkRepository.advance(TOPIC, partition, offset));
        return result == null ? 0 : result;
    }

    private long lastOffsetOf(int partition) {
        List<ConsumerWatermark> rows = consumerWatermarkRepository.findAll();
        return rows.stream()
                .filter(row -> row.getId().getTopic().equals(TOPIC) && row.getId().getPartitionNo() == partition)
                .findFirst()
                .orElseThrow()
                .getLastOffset();
    }
}
