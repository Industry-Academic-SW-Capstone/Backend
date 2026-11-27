package grit.stockIt.domain.matching.shard;

import grit.stockIt.contract.ExchangeQueryClient;
import grit.stockIt.contract.ExchangeTopics;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import grit.stockIt.domain.matching.entity.ExchangeOrder;
import grit.stockIt.domain.matching.queue.MatchingBatchProcessor;
import grit.stockIt.domain.matching.queue.MatchingCommand;
import grit.stockIt.domain.matching.queue.MatchingCommandHandler;
import grit.stockIt.domain.matching.queue.MatchingTopics;
import grit.stockIt.domain.matching.queue.OrderCommandPublisher;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import grit.stockIt.domain.matching.scheduler.MarketOrderExpiryScheduler;
import grit.stockIt.domain.matching.service.FillIdGenerator;
import grit.stockIt.domain.matching.service.LocalExchangeQueryClient;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.order.entity.OrderType;
import grit.stockIt.global.outbox.OutboxRelay;
import grit.stockIt.global.shard.ShardContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

// 파티션 0 은 샤드 0, 파티션 3 은 샤드 1 이다. 워커, 릴레이, 만료, 열린 주문 조회가 샤드를 골라 쓰는지 본다.
@TestPropertySource(properties = "outbox.relay.enabled=true")
@DisplayName("샤드 둘에 나눠 쓰는 체결, 릴레이, 만료, 열린 주문 조회 (통합)")
class ShardedMatchingIntegrationTest extends ShardedExchangeTestSupport {

    private static final String STOCK_A = "005930";
    private static final String STOCK_B = "000660";
    private static final int PARTITION_A = 0;
    private static final int PARTITION_B = 3;
    private static final long ACCOUNT_A = 101L;
    private static final long ACCOUNT_B = 202L;
    private static final BigDecimal PRICE = new BigDecimal("100");
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final List<String> EXCHANGE_TABLES =
            List.of("exchange_order", "exchange_fill", "consumer_watermark", "outbox_message");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registerShards(registry, "shard_matching_a", "shard_matching_b");
    }

    @Autowired private MatchingBatchProcessor processor;
    @Autowired private ExchangeOrderRepository exchangeOrderRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private MarketOrderExpiryScheduler expiryScheduler;
    @Autowired private LocalExchangeQueryClient exchangeQueryClient;
    @Autowired private MeterRegistry meterRegistry;
    @MockitoSpyBean private MatchingCommandHandler handler;
    @MockitoSpyBean private OrderCommandPublisher orderCommandPublisher;

    @BeforeEach
    void clearShards() {
        outboxRelay.stop();
        for (int shard = 0; shard < SHARDS; shard++) {
            ShardContext.run(shard, () -> jdbcTemplate.update("TRUNCATE TABLE " + String.join(", ", EXCHANGE_TABLES)));
        }
    }

    @AfterEach
    void stopRelay() {
        outboxRelay.stop();
    }

    @Test
    @DisplayName("묶음은 그 파티션의 샤드에만 주문, 체결, 워터마크, 통보를 남긴다")
    void commands_landOnPartitionShard() {
        processor.process(List.of(
                record(PARTITION_A, 0, sell(STOCK_A, 1L, ACCOUNT_A)),
                record(PARTITION_A, 1, buyFill(STOCK_A)),
                record(PARTITION_B, 0, sell(STOCK_B, 2L, ACCOUNT_B)),
                record(PARTITION_B, 1, buyFill(STOCK_B))));

        assertShardHoldsOnly(0, STOCK_A, PARTITION_A, ACCOUNT_A);
        assertShardHoldsOnly(1, STOCK_B, PARTITION_B, ACCOUNT_B);
    }

    @Test
    @DisplayName("묶음이 실패해 한 건씩 다시 처리해도 그 파티션의 샤드에만 쓴다")
    void fallbackOneByOne_staysOnPartitionShard() {
        MatchingCommand fill = buyFill(STOCK_B);
        doThrow(new RuntimeException("일시 실패")).doCallRealMethod().when(handler).handle(eq(fill), any());
        double fallbackBefore = meterRegistry.get("matching.batch.fallback").counter().count();

        processor.process(List.of(record(PARTITION_B, 0, sell(STOCK_B, 2L, ACCOUNT_B)), record(PARTITION_B, 1, fill)));

        assertThat(meterRegistry.get("matching.batch.fallback").counter().count() - fallbackBefore).isEqualTo(1.0);
        assertShardHoldsOnly(1, STOCK_B, PARTITION_B, ACCOUNT_B);
        for (String table : EXCHANGE_TABLES) {
            assertThat(countOn(0, table)).as("shard 0 " + table).isZero();
        }
    }

    @Test
    @DisplayName("두 샤드의 체결 번호를 합쳐도 겹치지 않는다")
    void fillIds_unique_acrossShards() {
        List<ConsumerRecord<String, MatchingCommand>> records = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            records.add(record(PARTITION_A, i, sell(STOCK_A, 10L + i, ACCOUNT_A)));
            records.add(record(PARTITION_B, i, sell(STOCK_B, 20L + i, ACCOUNT_B)));
        }
        for (int i = 3; i < 6; i++) {
            records.add(record(PARTITION_A, i, buyFill(STOCK_A)));
            records.add(record(PARTITION_B, i, buyFill(STOCK_B)));
        }

        processor.process(records);

        List<Long> idsA = fillIdsOn(0);
        List<Long> idsB = fillIdsOn(1);
        assertThat(idsA).hasSize(3).allSatisfy(id -> assertThat(FillIdGenerator.partitionOf(id)).isEqualTo(PARTITION_A));
        assertThat(idsB).hasSize(3).allSatisfy(id -> assertThat(FillIdGenerator.partitionOf(id)).isEqualTo(PARTITION_B));
        List<Long> all = new ArrayList<>(idsA);
        all.addAll(idsB);
        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("릴레이가 샤드마다 돌아 두 샤드의 아웃박스를 비우고 통보를 다 보낸다")
    void relay_drainsEveryShard() throws InterruptedException {
        processor.process(List.of(
                record(PARTITION_A, 0, sell(STOCK_A, 1L, ACCOUNT_A)),
                record(PARTITION_B, 0, sell(STOCK_B, 2L, ACCOUNT_B))));
        assertThat(countOn(0, "outbox_message")).isPositive();
        assertThat(countOn(1, "outbox_message")).isPositive();

        try (KafkaConsumer<String, String> consumer = reportConsumer()) {
            outboxRelay.start();

            assertThat(threadNames()).contains("outbox-relay-0", "outbox-relay-1");
            Set<String> keys = new HashSet<>();
            long deadline = System.currentTimeMillis() + WAIT.toMillis();
            while (System.currentTimeMillis() < deadline && !keys.containsAll(Set.of("101", "202"))) {
                for (ConsumerRecord<String, String> received : consumer.poll(Duration.ofMillis(500))) {
                    keys.add(received.key());
                }
            }
            assertThat(keys).contains(String.valueOf(ACCOUNT_A), String.valueOf(ACCOUNT_B));
        }
        awaitEmptyOutbox(0);
        awaitEmptyOutbox(1);
        assertThat(meterRegistry.get("outbox.pending.rows").tag("shard", "0").gauge()).isNotNull();
        assertThat(meterRegistry.get("outbox.pending.rows").tag("shard", "1").gauge()).isNotNull();
    }

    @Test
    @DisplayName("만료와 열린 주문 조회가 두 샤드를 다 본다")
    void expiry_and_openOrders_seeEveryShard() {
        ShardContext.run(0, () -> exchangeOrderRepository.save(ExchangeOrder.accept(31L, ACCOUNT_A, STOCK_A,
                OrderMethod.BUY, OrderType.MARKET, PRICE, 1)));
        ShardContext.run(1, () -> exchangeOrderRepository.save(ExchangeOrder.accept(32L, ACCOUNT_B, STOCK_B,
                OrderMethod.BUY, OrderType.MARKET, PRICE, 1)));

        expiryScheduler.expireMarketOrders();

        verify(orderCommandPublisher).expire(STOCK_A, 31L);
        verify(orderCommandPublisher).expire(STOCK_B, 32L);
        assertThat(exchangeQueryClient.openOrders()).containsExactlyInAnyOrder(
                new ExchangeQueryClient.OpenOrder(31L, STOCK_A), new ExchangeQueryClient.OpenOrder(32L, STOCK_B));
    }

    private void assertShardHoldsOnly(int shard, String stockCode, int partition, long accountId) {
        ShardContext.run(shard, () -> {
            assertThat(jdbcTemplate.queryForList("SELECT DISTINCT stock_code FROM exchange_order", String.class))
                    .containsExactly(stockCode);
            assertThat(jdbcTemplate.queryForList("SELECT DISTINCT stock_code FROM exchange_fill", String.class))
                    .containsExactly(stockCode);
            assertThat(jdbcTemplate.queryForList("SELECT partition_no FROM consumer_watermark", Integer.class))
                    .containsExactly(partition);
            assertThat(jdbcTemplate.queryForList("SELECT DISTINCT message_key FROM outbox_message", String.class))
                    .containsExactly(String.valueOf(accountId));
        });
    }

    private long countOn(int shard, String table) {
        return ShardContext.call(shard, () -> jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Long.class));
    }

    private List<Long> fillIdsOn(int shard) {
        return ShardContext.call(shard,
                () -> jdbcTemplate.queryForList("SELECT fill_id FROM exchange_fill ORDER BY fill_id", Long.class));
    }

    private void awaitEmptyOutbox(int shard) throws InterruptedException {
        long deadline = System.currentTimeMillis() + WAIT.toMillis();
        while (countOn(shard, "outbox_message") > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(countOn(shard, "outbox_message")).as("shard " + shard + " outbox").isZero();
    }

    private static Set<String> threadNames() {
        Set<String> names = new HashSet<>();
        Thread.getAllStackTraces().keySet().forEach(thread -> names.add(thread.getName()));
        return names;
    }

    private static MatchingCommand sell(String stockCode, long orderId, long accountId) {
        return MatchingCommand.order(stockCode, orderId, accountId, OrderMethod.SELL, OrderType.LIMIT, PRICE, 1,
                System.currentTimeMillis());
    }

    private static MatchingCommand buyFill(String stockCode) {
        return MatchingCommand.fill(stockCode, new LimitOrderFillEvent(UUID.randomUUID().toString(), OrderMethod.BUY,
                PRICE, 1, System.currentTimeMillis()), System.currentTimeMillis());
    }

    private static ConsumerRecord<String, MatchingCommand> record(int partition, long offset, MatchingCommand command) {
        return new ConsumerRecord<>(MatchingTopics.COMMANDS, partition, offset, command.stockCode(), command);
    }

    private static KafkaConsumer<String, String> reportConsumer() {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, ShardTestKafka.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "report-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(ExchangeTopics.REPORTS));
        return consumer;
    }
}
