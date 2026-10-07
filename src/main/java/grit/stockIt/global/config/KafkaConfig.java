package grit.stockIt.global.config;

import grit.stockIt.domain.matching.queue.InvalidMatchingCommandException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "matching.queue.enabled", havingValue = "true")
public class KafkaConfig {

    private static final String DLT_SUFFIX = ".DLT";
    private static final long INITIAL_BACKOFF_MS = 200L;
    private static final long MAX_BACKOFF_MS = 5_000L;

    // 독 메시지(역직렬화 실패, 검증 실패)만 <토픽>.DLT 로 보낸다. 그 밖의 실패는 건너뛰지 않고 끝없이 다시 시도한다 —
    // 건너뛰면 그 명령은 유실되고 종목 안의 순서도 깨진다. 그동안 파티션이 멈추므로 lag 으로 드러난다.
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate,
                                                 ProducerFactory<Object, Object> producerFactory) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterTemplates(kafkaTemplate, producerFactory),
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1)
        );

        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_BACKOFF_MS, 2.0);
        backOff.setMaxInterval(MAX_BACKOFF_MS);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(InvalidMatchingCommandException.class);
        return errorHandler;
    }

    // 역직렬화에 실패한 메시지는 원래 바이트 그대로, 나머지는 JSON 으로 DLT 에 남긴다.
    private Map<Class<?>, KafkaOperations<?, ?>> deadLetterTemplates(KafkaTemplate<Object, Object> jsonTemplate,
                                                                     ProducerFactory<Object, Object> producerFactory) {
        KafkaTemplate<Object, Object> bytesTemplate = new KafkaTemplate<>(producerFactory.copyWithConfigurationOverride(
                Map.of(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)));

        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, bytesTemplate);
        templates.put(Object.class, jsonTemplate);
        return templates;
    }
}
