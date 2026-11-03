package grit.stockIt.global.client;

import grit.stockIt.contract.ExchangeQueryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

// 거래소 구현(LocalExchangeQueryClient)이 같은 프로세스에 없을 때만 HTTP 클라이언트를 만든다.
// 자동 설정은 컴포넌트 스캔이 끝난 뒤 평가되므로 "이미 있는가"를 믿을 수 있다.
@AutoConfiguration
public class ExchangeQueryClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ExchangeQueryClient.class)
    public ExchangeQueryClient httpExchangeQueryClient(RestClient.Builder builder,
                                                       @Value("${stockit.services.exchange-url}") String exchangeUrl) {
        return new HttpExchangeQueryClient(InternalRestClients.create(builder, exchangeUrl));
    }
}
