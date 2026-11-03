package grit.stockIt.global.client;

import grit.stockIt.contract.BrokerQueryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

// 증권사 구현(LocalBrokerQueryClient)이 같은 프로세스에 없을 때만 HTTP 클라이언트를 만든다.
@AutoConfiguration
public class BrokerQueryClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BrokerQueryClient.class)
    public BrokerQueryClient httpBrokerQueryClient(RestClient.Builder builder,
                                                   @Value("${stockit.services.broker-url}") String brokerUrl) {
        return new HttpBrokerQueryClient(InternalRestClients.create(builder, brokerUrl));
    }
}
