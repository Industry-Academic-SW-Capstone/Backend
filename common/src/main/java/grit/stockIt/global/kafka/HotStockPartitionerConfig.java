package grit.stockIt.global.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

// 표가 비면 파티셔너를 달지 않아 Kafka 기본 동작이 그대로다.
@Configuration
@ConditionalOnExpression("!'${matching.hot-stocks:}'.trim().isEmpty()")
public class HotStockPartitionerConfig {

    @Bean
    DefaultKafkaProducerFactoryCustomizer hotStockPartitionerCustomizer(
            @Value("${matching.hot-stocks:}") String hotStocks) {
        HotStockPartitioner.parse(hotStocks);
        return factory -> factory.updateConfigs(Map.of(
                ProducerConfig.PARTITIONER_CLASS_CONFIG, HotStockPartitioner.class,
                HotStockPartitioner.HOT_STOCKS_CONFIG, hotStocks));
    }
}
