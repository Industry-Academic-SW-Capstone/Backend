package grit.stockIt.global.dlt;

import grit.stockIt.global.exception.BadRequestException;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

// 재시도를 다 써 DLT 로 간 메시지를 사람이 보고 원래 토픽으로 다시 보내거나 버린다.
// 받는 쪽이 모두 멱등(체결 번호·hold_release·processed_event·주문 번호 집합)이라 같은 경로로 다시 흘려보내면 된다.
// 어디까지 처리했는지는 이 그룹의 커밋된 offset 이다 — 그 뒤가 아직 처리하지 않은 DLT 메시지다.
@Slf4j
@Service
public class DeadLetterService {

    static final String GROUP = "dlt-replay";
    private static final String DLT_SUFFIX = ".DLT";
    private static final Duration POLL = Duration.ofMillis(500);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaProperties kafkaProperties;
    private final KafkaTemplate<byte[], byte[]> bytesTemplate;
    private final MeterRegistry registry;
    private final Set<String> topics;

    @SuppressWarnings({"unchecked", "rawtypes"})
    public DeadLetterService(KafkaProperties kafkaProperties, ProducerFactory<Object, Object> producerFactory,
                             MeterRegistry registry,
                             @Value("${dlt.topics:matching.commands.DLT,exchange.reports.DLT,member.events.DLT,extra.events.DLT}")
                             List<String> topics) {
        this.kafkaProperties = kafkaProperties;
        // DLT 에 남은 바이트를 그대로 다시 보낸다. 역직렬화에 실패해 온 메시지도 원래 모습으로 돌아간다.
        this.bytesTemplate = new KafkaTemplate(producerFactory.copyWithConfigurationOverride(Map.of(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));
        this.registry = registry;
        this.topics = new LinkedHashSet<>(topics);
    }

    public Set<String> topics() {
        return topics;
    }

    // 아직 처리하지 않은 메시지. 개수는 전부, 내용은 앞에서 limit 개까지.
    public synchronized DeadLetterBatch pending(String dltTopic, int limit) {
        Pending pending = read(dltTopic);
        List<DeadLetter> messages = pending.records().stream().limit(limit).map(DeadLetterService::toDeadLetter).toList();
        return new DeadLetterBatch(dltTopic, pending.records().size(), messages);
    }

    // 지금까지 쌓인 것을 모두 원래 토픽에 같은 키로 다시 보내고, 브로커가 받으면 처리한 위치를 끝으로 옮긴다.
    // 보내다 실패하면 위치를 옮기지 않는다 — 다시 누르면 일부가 두 번 가지만 받는 쪽이 거른다.
    public synchronized int replay(String dltTopic) {
        Pending pending = read(dltTopic);
        CompletableFuture<?>[] sends = pending.records().stream()
                .map(record -> bytesTemplate.send(originalTopic(record), record.key(), record.value()))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(sends).get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DLT 재전송 대기 중 중단됐습니다.", e);
        } catch (Exception e) {
            throw new IllegalStateException("DLT 재전송 실패. 처리 위치를 옮기지 않았습니다. topic=" + dltTopic, e);
        }
        commit(pending);
        registry.counter("dlt.replayed", "topic", dltTopic).increment(pending.records().size());
        log.info("DLT 재전송: topic={} count={}", dltTopic, pending.records().size());
        return pending.records().size();
    }

    // 다시 보낼 가치가 없는 메시지(형식이 깨진 명령 등)를 확인한 뒤 버린다. 위치만 끝으로 옮긴다.
    public synchronized int skip(String dltTopic) {
        Pending pending = read(dltTopic);
        commit(pending);
        registry.counter("dlt.skipped", "topic", dltTopic).increment(pending.records().size());
        log.info("DLT 건너뜀: topic={} count={}", dltTopic, pending.records().size());
        return pending.records().size();
    }

    private Pending read(String dltTopic) {
        if (!topics.contains(dltTopic)) {
            throw new BadRequestException("다룰 수 없는 DLT 입니다: " + dltTopic);
        }
        try (KafkaConsumer<byte[], byte[]> consumer = newConsumer()) {
            List<TopicPartition> partitions = consumer.partitionsFor(dltTopic).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            // 읽기 시작한 순간의 끝까지만 읽는다. 그 뒤에 들어온 것은 다음 차례다.
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(Set.copyOf(partitions));
            Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions);
            for (TopicPartition partition : partitions) {
                OffsetAndMetadata done = committed.get(partition);
                consumer.seek(partition, done != null ? done.offset() : beginning.get(partition));
            }

            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.currentTimeMillis() + READ_TIMEOUT.toMillis();
            while (!reachedEnd(consumer, partitions, end)) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("DLT 를 끝까지 읽지 못했습니다. topic=" + dltTopic);
                }
                for (ConsumerRecord<byte[], byte[]> record : consumer.poll(POLL)) {
                    if (record.offset() < end.get(new TopicPartition(record.topic(), record.partition()))) {
                        records.add(record);
                    }
                }
            }
            return new Pending(records, end);
        }
    }

    private void commit(Pending pending) {
        try (KafkaConsumer<byte[], byte[]> consumer = newConsumer()) {
            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            pending.end().forEach((partition, offset) -> offsets.put(partition, new OffsetAndMetadata(offset)));
            consumer.commitSync(offsets);
        }
    }

    private static boolean reachedEnd(KafkaConsumer<byte[], byte[]> consumer, List<TopicPartition> partitions,
                                      Map<TopicPartition, Long> end) {
        return partitions.stream().allMatch(partition -> consumer.position(partition) >= end.get(partition));
    }

    private KafkaConsumer<byte[], byte[]> newConsumer() {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new KafkaConsumer<>(properties, new ByteArrayDeserializer(), new ByteArrayDeserializer());
    }

    // 원래 토픽은 DLT 를 만든 쪽이 헤더에 남긴다. 없으면 이름에서 .DLT 를 뗀다.
    private static String originalTopic(ConsumerRecord<byte[], byte[]> record) {
        String header = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        if (header != null) {
            return header;
        }
        return record.topic().substring(0, record.topic().length() - DLT_SUFFIX.length());
    }

    private static DeadLetter toDeadLetter(ConsumerRecord<byte[], byte[]> record) {
        return new DeadLetter(
                record.partition(),
                record.offset(),
                originalTopic(record),
                record.key() == null ? null : new String(record.key(), StandardCharsets.UTF_8),
                header(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                record.value() == null ? null : new String(record.value(), StandardCharsets.UTF_8));
    }

    private static String header(ConsumerRecord<byte[], byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private record Pending(List<ConsumerRecord<byte[], byte[]>> records, Map<TopicPartition, Long> end) {
    }

    public record DeadLetter(int partition, long offset, String originalTopic, String key, String exception,
                             String exceptionMessage, String payload) {
    }

    public record DeadLetterBatch(String topic, int pendingCount, List<DeadLetter> messages) {
    }
}
