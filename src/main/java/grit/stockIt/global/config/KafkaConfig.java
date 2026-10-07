package grit.stockIt.global.config;

import grit.stockIt.domain.matching.queue.InvalidMatchingCommandException;
import grit.stockIt.domain.settlement.queue.InvalidSettlementRequestException;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "matching.queue.enabled", havingValue = "true")
public class KafkaConfig {

    private static final String DLT_SUFFIX = ".DLT";
    private static final long INITIAL_BACKOFF_MS = 200L;
    private static final long MAX_BACKOFF_MS = 5_000L;
    private static final int SETTLEMENT_MAX_RETRIES = 10;

    // 기본 리스너 팩토리(체결 워커)가 쓴다. 이 타입의 빈은 하나만 둔다 — 둘이면 Boot 가 어느 쪽도 붙이지 않는다.
    // 독 메시지(역직렬화 실패, 검증 실패)만 <토픽>.DLT 로 보낸다. 그 밖의 실패는 건너뛰지 않고 끝없이 다시 시도한다 —
    // 건너뛰면 그 명령은 유실되고 종목 안의 순서도 깨진다. 그동안 파티션이 멈추므로 lag 으로 드러난다.
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> kafkaTemplate,
                                                 ProducerFactory<Object, Object> producerFactory) {
        ExponentialBackOff backOff = new ExponentialBackOff(INITIAL_BACKOFF_MS, 2.0);
        backOff.setMaxInterval(MAX_BACKOFF_MS);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                deadLetterRecoverer(kafkaTemplate, producerFactory), backOff);
        errorHandler.addNotRetryableExceptions(InvalidMatchingCommandException.class);
        return errorHandler;
    }

    // 정산은 정해진 횟수만 다시 시도하고 DLT 로 넘긴다. 체결은 이미 DB 에 있어 복구 배치가 다시 정산하므로
    // 건너뛰어도 유실이 아니다. 끝없이 붙잡으면 계좌 하나 때문에 그 파티션의 다른 계좌 정산이 모두 멈춘다.
    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> settlementListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaTemplate<Object, Object> kafkaTemplate,
            ProducerFactory<Object, Object> producerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(SETTLEMENT_MAX_RETRIES);
        backOff.setInitialInterval(INITIAL_BACKOFF_MS);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(MAX_BACKOFF_MS);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                deadLetterRecoverer(kafkaTemplate, producerFactory), backOff);
        errorHandler.addNotRetryableExceptions(InvalidSettlementRequestException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    private DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<Object, Object> jsonTemplate,
                                                              ProducerFactory<Object, Object> producerFactory) {
        return new DeadLetterPublishingRecoverer(
                deadLetterTemplates(jsonTemplate, producerFactory),
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1)
        );
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
