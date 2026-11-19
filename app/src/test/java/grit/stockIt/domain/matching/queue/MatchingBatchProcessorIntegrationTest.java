package grit.stockIt.domain.matching.queue;

import grit.stockIt.contract.ExecutionReport;
import grit.stockIt.contract.ExecutionReportType;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.entity.ExchangeOrderStatus;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.global.support.IntegrationTestSupport;
import grit.stockIt.global.support.OutboxReports;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("MatchingBatchProcessor — 파티션별 한 트랜잭션, 실패하면 한 건씩 (통합)")
class MatchingBatchProcessorIntegrationTest extends IntegrationTestSupport {

    private static final String STOCK_CODE = "005930";
    private static final BigDecimal PRICE = new BigDecimal("100");
    private static final long ACCOUNT_ID = 77L;

    @Autowired private MatchingBatchProcessor processor;
    @Autowired private ExchangeOrderRepository exchangeOrderRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;
    @MockitoSpyBean private MatchingCommandHandler handler;

    @Test
    @DisplayName("한 배치의 체결 여러 개가 잔여 수량을 이어 받는다")
    void 한_배치의_체결_여러_개가_잔여_수량을_이어_받는다() {
        placeSell(1L, 1);
        placeSell(2L, 1);
        placeSell(3L, 1);

        processor.process(List.of(record(0, 0, buyFill(1)), record(0, 1, buyFill(1)), record(0, 2, buyFill(1))));

        assertThat(statusOf(1L)).isEqualTo(ExchangeOrderStatus.FILLED);
        assertThat(statusOf(2L)).isEqualTo(ExchangeOrderStatus.FILLED);
        assertThat(statusOf(3L)).isEqualTo(ExchangeOrderStatus.FILLED);
        assertThat(fillCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("같은 배치에서 접수한 주문을 뒤 체결이 채운다")
    void 같은_배치에서_접수한_주문을_뒤_체결이_채운다() {
        MatchingCommand order = MatchingCommand.order(STOCK_CODE, 10L, ACCOUNT_ID, OrderMethod.SELL, OrderType.LIMIT,
                PRICE, 1, System.currentTimeMillis());

        processor.process(List.of(record(0, 0, order), record(0, 1, buyFill(1))));

        assertThat(statusOf(10L)).isEqualTo(ExchangeOrderStatus.FILLED);
    }

    @Test
    @DisplayName("같은 배치의 체결이 먼저 채운 주문은 취소되지 않는다")
    void 같은_배치의_체결이_먼저_채운_주문은_취소되지_않는다() {
        placeSell(1L, 1);

        processor.process(List.of(record(0, 0, buyFill(1)),
                record(0, 1, MatchingCommand.cancel(STOCK_CODE, 1L, System.currentTimeMillis()))));

        assertThat(statusOf(1L)).isEqualTo(ExchangeOrderStatus.FILLED);
        assertThat(OutboxReports.take(jdbcTemplate, ACCOUNT_ID)).extracting(ExecutionReport::type)
                .containsExactly(ExecutionReportType.FILL);
    }

    @Test
    @DisplayName("배치 전체를 다시 받으면 모두 중복으로 거른다")
    void 배치_전체를_다시_받으면_모두_중복으로_거른다() {
        placeSell(1L, 1);
        placeSell(2L, 1);
        placeSell(3L, 1);
        placeSell(4L, 1);
        List<ConsumerRecord<String, MatchingCommand>> batch = List.of(record(0, 0, buyFill(1)), record(0, 1, buyFill(1)));
        processor.process(batch);
        double duplicatesBefore = counter("matching.fill.duplicate");
        long filledBefore = e2eCount();

        processor.process(batch);

        assertThat(fillCount()).isEqualTo(2);
        assertThat(counter("matching.fill.duplicate") - duplicatesBefore).isEqualTo(2.0);
        assertThat(e2eCount() - filledBefore).isZero();
    }

    @Test
    @DisplayName("배치가 일시 실패하면 한 건씩 다시 처리해 모두 반영한다")
    void 배치가_일시_실패하면_한_건씩_다시_처리해_모두_반영한다() {
        placeSell(1L, 1);
        placeSell(2L, 1);
        placeSell(3L, 1);
        List<ConsumerRecord<String, MatchingCommand>> batch = failingOnceAtSecond();
        double fallbackBefore = counter("matching.batch.fallback");
        long batchCountBefore = batchSize().count();
        double batchTotalBefore = batchSize().totalAmount();

        processor.process(batch);

        assertThat(fillCount()).isEqualTo(3);
        assertThat(watermarkOf(0)).contains(2L);
        assertThat(counter("matching.batch.fallback") - fallbackBefore).isEqualTo(1.0);
        assertThat(batchSize().count() - batchCountBefore).as("커밋한 트랜잭션 수(한 건씩 셋)").isEqualTo(3);
        assertThat(batchSize().totalAmount() - batchTotalBefore).isEqualTo(3.0);
    }

    @Test
    @DisplayName("한 건짜리 묶음이 실패하면 다시 처리하지 않고 바로 그 레코드로 멈춘다")
    void 한_건짜리_묶음이_실패하면_다시_처리하지_않고_바로_그_레코드로_멈춘다() {
        placeSell(1L, 1);
        MatchingCommand command = buyFill(1);
        doThrow(new RuntimeException("일시 실패")).when(handler).handle(eq(command), any());
        ConsumerRecord<String, MatchingCommand> only = record(0, 0, command);
        double fallbackBefore = counter("matching.batch.fallback");
        long batchCountBefore = batchSize().count();

        assertThatThrownBy(() -> processor.process(List.of(only)))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getRecord()).isSameAs(only);
                    assertThat(e.getCause()).hasMessage("일시 실패");
                });

        verify(handler, times(1)).handle(eq(command), any());
        assertThat(counter("matching.batch.fallback") - fallbackBefore).isZero();
        assertThat(batchSize().count() - batchCountBefore).isZero();
        assertThat(fillCount()).isZero();
    }

    @Test
    @DisplayName("중간 명령이 검증에 실패하면 앞은 반영하고 그 명령에서 멈춘다")
    void 중간_명령이_검증에_실패하면_앞은_반영하고_그_명령에서_멈춘다() {
        placeSell(1L, 1);
        placeSell(2L, 1);
        ConsumerRecord<String, MatchingCommand> invalid = record(0, 1, buyFill(0));
        List<ConsumerRecord<String, MatchingCommand>> batch = List.of(record(0, 0, buyFill(1)), invalid,
                record(0, 2, buyFill(1)));

        assertThatThrownBy(() -> processor.process(batch))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(InvalidMatchingCommandException.class);
                    assertThat(e.getRecord()).isSameAs(invalid);
                });

        assertThat(fillCount()).isEqualTo(1);
        assertThat(statusOf(1L)).isEqualTo(ExchangeOrderStatus.FILLED);
        assertThat(statusOf(2L)).isEqualTo(ExchangeOrderStatus.OPEN);
        assertThat(watermarkOf(0)).contains(0L);
    }

    @Test
    @DisplayName("읽지 못한 레코드는 앞까지만 반영하고 그 레코드에서 멈춘다")
    void 읽지_못한_레코드는_앞까지만_반영하고_그_레코드에서_멈춘다() {
        placeSell(1L, 1);
        placeSell(2L, 1);
        ConsumerRecord<String, MatchingCommand> unreadable = unreadableRecord(0, 1);
        List<ConsumerRecord<String, MatchingCommand>> batch = List.of(record(0, 0, buyFill(1)), unreadable,
                record(0, 2, buyFill(1)));
        double fallbackBefore = counter("matching.batch.fallback");

        assertThatThrownBy(() -> processor.process(batch))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getCause()).isInstanceOf(DeserializationException.class);
                    assertThat(e.getRecord()).isSameAs(unreadable);
                });

        assertThat(fillCount()).isEqualTo(1);
        assertThat(watermarkOf(0)).contains(0L);
        assertThat(counter("matching.batch.fallback") - fallbackBefore).isZero();
    }

    @Test
    @DisplayName("재처리된 배치도 지표는 한 번만 센다")
    void 재처리된_배치도_지표는_한_번만_센다() {
        placeSell(1L, 1);
        placeSell(2L, 1);
        placeSell(3L, 1);
        List<ConsumerRecord<String, MatchingCommand>> batch = failingOnceAtSecond();
        long filledBefore = e2eCount();
        long waitBefore = meterRegistry.get("matching.fill.queue.wait").timer().count();
        long durationBefore = commandDurationCount();

        processor.process(batch);

        assertThat(e2eCount() - filledBefore).isEqualTo(3);
        assertThat(meterRegistry.get("matching.fill.queue.wait").timer().count() - waitBefore).isEqualTo(3);
        assertThat(commandDurationCount() - durationBefore).isEqualTo(3);
    }

    @Test
    @DisplayName("동시에 같은 배치를 처리해도 한쪽만 반영한다")
    void 동시에_같은_배치를_처리해도_한쪽만_반영한다() throws InterruptedException {
        for (long id = 1; id <= 6; id++) {
            placeSell(id, 1);
        }
        List<ConsumerRecord<String, MatchingCommand>> batch = List.of(
                record(0, 0, buyFill(1)), record(0, 1, buyFill(1)), record(0, 2, buyFill(1)));
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    processor.process(batch);
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();

        assertThat(errors).isEmpty();
        assertThat(fillCount()).isEqualTo(3);
        assertThat(watermarkOf(0)).contains(2L);
    }

    @Test
    @DisplayName("파티션이 섞인 poll 은 파티션별로 처리한다")
    void 파티션이_섞인_poll은_파티션별로_처리한다() {
        placeSell(1L, 1);
        placeSell(2L, 1);
        placeSell(3L, 1);

        processor.process(List.of(record(0, 5, buyFill(1)), record(1, 8, buyFill(1)), record(0, 6, buyFill(1))));

        assertThat(fillCount()).isEqualTo(3);
        assertThat(watermarkOf(0)).contains(6L);
        assertThat(watermarkOf(1)).contains(8L);
    }

    @Test
    @DisplayName("레코드 하나짜리 배치는 지금과 같게 처리한다")
    void 레코드_하나짜리_배치는_지금과_같게_처리한다() {
        placeSell(1L, 1);
        double fallbackBefore = counter("matching.batch.fallback");
        long batchCountBefore = batchSize().count();

        processor.process(List.of(record(0, 3, buyFill(1))));

        assertThat(fillCount()).isEqualTo(1);
        assertThat(watermarkOf(0)).contains(3L);
        assertThat(OutboxReports.take(jdbcTemplate, ACCOUNT_ID)).extracting(ExecutionReport::type)
                .containsExactly(ExecutionReportType.FILL);
        assertThat(counter("matching.batch.fallback") - fallbackBefore).isZero();
        assertThat(batchSize().count() - batchCountBefore).isEqualTo(1);
    }

    private List<ConsumerRecord<String, MatchingCommand>> failingOnceAtSecond() {
        MatchingCommand second = buyFill(1);
        doThrow(new RuntimeException("일시 실패")).doCallRealMethod().when(handler).handle(eq(second), any());
        return List.of(record(0, 0, buyFill(1)), record(0, 1, second), record(0, 2, buyFill(1)));
    }

    private void placeSell(Long orderId, int quantity) {
        exchangeOrderRepository.save(ExchangeOrder.accept(orderId, ACCOUNT_ID, STOCK_CODE, OrderMethod.SELL,
                OrderType.LIMIT, PRICE, quantity));
    }

    private static MatchingCommand buyFill(int quantity) {
        return MatchingCommand.fill(STOCK_CODE, new LimitOrderFillEvent(UUID.randomUUID().toString(), OrderMethod.BUY,
                PRICE, quantity, System.currentTimeMillis()), System.currentTimeMillis());
    }

    private static ConsumerRecord<String, MatchingCommand> record(int partition, long offset, MatchingCommand command) {
        return new ConsumerRecord<>(MatchingTopics.COMMANDS, partition, offset, STOCK_CODE, command);
    }

    // 배치 리스너에서는 ErrorHandlingDeserializer 가 값을 null 로 두고 예외를 헤더에 싣는다.
    private static ConsumerRecord<String, MatchingCommand> unreadableRecord(int partition, long offset) {
        byte[] data = "not-json".getBytes(StandardCharsets.UTF_8);
        RecordHeaders headers = new RecordHeaders();
        SerializationUtils.deserializationException(headers, data,
                new DeserializationException("읽을 수 없음", data, false, new IllegalStateException()), false);
        return new ConsumerRecord<>(MatchingTopics.COMMANDS, partition, offset, 0L, TimestampType.CREATE_TIME,
                0, data.length, STOCK_CODE, null, headers, Optional.empty());
    }

    private ExchangeOrderStatus statusOf(Long orderId) {
        return exchangeOrderRepository.findById(orderId).orElseThrow().getStatus();
    }

    private int fillCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM exchange_fill", Integer.class);
    }

    private Optional<Long> watermarkOf(int partition) {
        return jdbcTemplate.queryForList("SELECT last_offset FROM consumer_watermark WHERE topic = ? AND partition_no = ?",
                Long.class, MatchingTopics.COMMANDS, partition).stream().findFirst();
    }

    private double counter(String name) {
        return meterRegistry.get(name).counter().count();
    }

    private DistributionSummary batchSize() {
        return meterRegistry.get("matching.batch.size").summary();
    }

    private long e2eCount() {
        return meterRegistry.get("matching.fill.e2e").timer().count();
    }

    private long commandDurationCount() {
        return meterRegistry.find("matching.command.duration").tag("type", "FILL").timers().stream()
                .mapToLong(timer -> timer.count()).sum();
    }
}
