package grit.stockIt.global.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

// 아웃박스에 적힌 메시지를 적힌 순서(id)대로 Kafka 로 보내고, 브로커가 받은 뒤에 지운다.
// 보내고 지우기 전에 죽으면 다음에 다시 보낸다 — 최소 한 번 전달이라 받는 쪽이 중복을 거른다.
// 스케줄러가 아니라 전용 스레드다. 측정 환경은 스케줄링을 끄지만 통보는 멈추면 안 된다.
@Slf4j
@Component
public class OutboxRelay implements SmartLifecycle {

    // 여러 인스턴스가 떠도 한 곳만 보낸다. 둘이 나눠 보내면 같은 키의 순서가 섞인다.
    private static final long RELAY_LOCK_KEY = 0x0B0B0B0BL;
    private static final String SELECT_SQL =
            "SELECT id, topic, message_key, payload FROM outbox_message ORDER BY id LIMIT ?";
    private static final String DELETE_SQL = "DELETE FROM outbox_message WHERE id = ANY (?)";

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final Counter published;
    private final Counter failures;

    @Value("${outbox.relay.enabled:true}")
    private boolean enabled;

    @Value("${outbox.relay.batch-size:500}")
    private int batchSize;

    // 보낼 것이 없을 때 쉬는 시간. 체결 → 정산 지연에 그대로 더해진다.
    @Value("${outbox.relay.poll-interval:20ms}")
    private Duration pollInterval;

    @Value("${outbox.relay.send-timeout:5s}")
    private Duration sendTimeout;

    private volatile boolean running;
    private Thread worker;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public OutboxRelay(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate,
                       ProducerFactory<Object, Object> producerFactory, MeterRegistry registry) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        // 아웃박스에는 이미 JSON 으로 적혀 있으므로 바이트 그대로 보낸다. 받는 쪽 JsonDeserializer 가 읽는 형식과 같다.
        this.kafkaTemplate = new KafkaTemplate(producerFactory.copyWithConfigurationOverride(
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
        this.published = Counter.builder("outbox.relay.published")
                .description("아웃박스에서 Kafka 로 보낸 메시지 수").register(registry);
        this.failures = Counter.builder("outbox.relay.failures")
                .description("아웃박스 전송 실패 횟수 — 지우지 않고 다음에 다시 보낸다").register(registry);
    }

    // 한 번에 batch-size 만큼 보낸다. 보낸 건수를 돌려준다. 하나라도 실패하면 아무것도 지우지 않는다.
    public int relayOnce() {
        Integer sent = transactionTemplate.execute(status -> {
            Boolean locked = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, RELAY_LOCK_KEY);
            if (!Boolean.TRUE.equals(locked)) {
                return 0;
            }
            List<Row> rows = jdbcTemplate.query(SELECT_SQL, (rs, i) -> new Row(
                    rs.getLong("id"), rs.getString("topic"), rs.getString("message_key"), rs.getString("payload")),
                    batchSize);
            if (rows.isEmpty()) {
                return 0;
            }
            send(rows);
            Long[] ids = rows.stream().map(Row::id).toArray(Long[]::new);
            jdbcTemplate.update(connection -> {
                var statement = connection.prepareStatement(DELETE_SQL);
                Array array = connection.createArrayOf("bigint", ids);
                statement.setArray(1, array);
                return statement;
            });
            return rows.size();
        });
        int count = sent == null ? 0 : sent;
        published.increment(count);
        return count;
    }

    private void send(List<Row> rows) {
        CompletableFuture<?>[] futures = rows.stream()
                .map(row -> kafkaTemplate.send(row.topic(), row.key(), row.payload().getBytes(StandardCharsets.UTF_8)))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("아웃박스 전송 대기 중 중단됐습니다.", e);
        } catch (Exception e) {
            throw new IllegalStateException("아웃박스 전송 실패. 지우지 않고 다시 보낸다.", e);
        }
    }

    private void loop() {
        while (running) {
            try {
                if (relayOnce() < batchSize) {
                    sleep(pollInterval);
                }
            } catch (RuntimeException e) {
                failures.increment();
                log.warn("아웃박스 전송 실패. 잠시 뒤 다시 보냅니다.", e);
                sleep(Duration.ofSeconds(1));
            }
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    @Override
    public void start() {
        if (!enabled || running) {
            return;
        }
        running = true;
        worker = Thread.ofPlatform().name("outbox-relay").daemon(true).start(this::loop);
    }

    @Override
    public void stop() {
        running = false;
        if (worker == null) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private record Row(long id, String topic, String key, String payload) {
    }
}
