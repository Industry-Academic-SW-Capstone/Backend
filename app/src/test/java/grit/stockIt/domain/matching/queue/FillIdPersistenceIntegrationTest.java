package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import grit.stockIt.domain.matching.service.FillIdGenerator;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.global.support.IntegrationTestSupport;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@DisplayName("체결 번호 — 파티션 Snowflake 가 원장과 워터마크에 이어진다 (통합)")
@TestPropertySource(properties = "matching.queue.batch-max=3")
class FillIdPersistenceIntegrationTest extends IntegrationTestSupport {

    private static final String STOCK_CODE = "005930";
    private static final BigDecimal PRICE = new BigDecimal("100");
    private static final long ACCOUNT_ID = 77L;
    private static final int PARTITION = 3;

    @Autowired private MatchingBatchProcessor processor;
    @Autowired private ExchangeOrderRepository exchangeOrderRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private MatchingCommandHandler handler;
    @MockitoSpyBean private FillIdGenerator spiedGenerator;

    @Test
    @DisplayName("체결 번호는 그 파티션의 번호이고, 워터마크는 가장 큰 번호를 남긴다")
    void fills_getSnowflakeIds_andWatermarkKeepsLast() {
        long now = System.currentTimeMillis();
        MatchingCommand sell1 = MatchingCommand.order(STOCK_CODE, 1L, ACCOUNT_ID, OrderMethod.SELL, OrderType.LIMIT, PRICE, 1, now);
        MatchingCommand sell2 = MatchingCommand.order(STOCK_CODE, 2L, ACCOUNT_ID, OrderMethod.SELL, OrderType.LIMIT, PRICE, 1, now);

        processor.process(List.of(record(0, sell1), record(1, sell2), record(2, buyFill(2))));

        List<Long> ids = fillIds();
        assertThat(ids).hasSize(2);
        assertThat(ids).allSatisfy(id -> assertThat(FillIdGenerator.partitionOf(id)).isEqualTo(PARTITION));
        assertThat(lastFillId()).isEqualTo(ids.get(1));
    }

    @Test
    @DisplayName("묶음이 롤백된 뒤 한 건씩 다시 처리해도 번호는 늘어나고 겹치지 않는다")
    void rolledBackBatch_nextIdsStillIncrease() {
        placeSell(1L);
        placeSell(2L);
        placeSell(3L);
        List<Long> issued = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            Long id = (Long) invocation.callRealMethod();
            issued.add(id);
            return id;
        }).when(spiedGenerator).next(anyInt());
        MatchingCommand second = buyFill(1);
        doThrow(new RuntimeException("일시 실패")).doCallRealMethod().when(handler).handle(eq(second), any());

        processor.process(List.of(record(0, buyFill(1)), record(1, second), record(2, buyFill(1))));

        // 첫 번호는 롤백된 묶음에서 나왔고, 나머지 셋이 한 건씩 다시 처리해 커밋된 번호다.
        assertThat(issued).hasSize(4);
        List<Long> committed = issued.subList(1, 4);
        assertThat(fillIds()).containsExactlyInAnyOrderElementsOf(committed);
        assertThat(committed).allSatisfy(id -> assertThat(id).isGreaterThan(issued.get(0)));
        assertThat(committed.get(0)).isLessThan(committed.get(1));
        assertThat(committed.get(1)).isLessThan(committed.get(2));
        assertThat(lastFillId()).isEqualTo(committed.get(2));
    }

    @Test
    @DisplayName("메모리가 빈 생성기도 저장된 마지막 번호 뒤에서 시작한다")
    void handover_startsAfterPersistedLastFillId() {
        placeSell(1L);
        placeSell(2L);
        processor.process(List.of(record(0, buyFill(1))));
        long future = (System.currentTimeMillis() - FillIdGenerator.EPOCH_MILLIS + 3_600_000L) << 14 | ((long) PARTITION << 6);
        jdbcTemplate.update("UPDATE consumer_watermark SET last_fill_id = ? WHERE topic = ? AND partition_no = ?",
                future, MatchingTopics.COMMANDS, PARTITION);

        processor.process(List.of(record(1, buyFill(1))));

        List<Long> ids = fillIds();
        assertThat(ids).hasSize(2);
        assertThat(ids.get(1)).isGreaterThan(future);
        assertThat(lastFillId()).isEqualTo(ids.get(1));
    }

    private void placeSell(Long orderId) {
        exchangeOrderRepository.save(ExchangeOrder.accept(orderId, ACCOUNT_ID, STOCK_CODE, OrderMethod.SELL,
                OrderType.LIMIT, PRICE, 1));
    }

    private static MatchingCommand buyFill(int quantity) {
        return MatchingCommand.fill(STOCK_CODE, new LimitOrderFillEvent(UUID.randomUUID().toString(), OrderMethod.BUY,
                PRICE, quantity, System.currentTimeMillis()), System.currentTimeMillis());
    }

    private static ConsumerRecord<String, MatchingCommand> record(long offset, MatchingCommand command) {
        return new ConsumerRecord<>(MatchingTopics.COMMANDS, PARTITION, offset, STOCK_CODE, command);
    }

    private List<Long> fillIds() {
        return jdbcTemplate.queryForList("SELECT fill_id FROM exchange_fill ORDER BY fill_id", Long.class);
    }

    private long lastFillId() {
        return jdbcTemplate.queryForObject("SELECT last_fill_id FROM consumer_watermark WHERE topic = ? AND partition_no = ?",
                Long.class, MatchingTopics.COMMANDS, PARTITION);
    }
}
