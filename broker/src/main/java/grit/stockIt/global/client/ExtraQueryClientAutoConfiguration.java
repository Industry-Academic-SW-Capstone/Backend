package grit.stockIt.global.client;

import grit.stockIt.contract.ExtraQueryClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

// 부가 구현(LocalExtraQueryClient)이 같은 프로세스에 없을 때만 HTTP 클라이언트를 만든다.
@AutoConfiguration
public class ExtraQueryClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ExtraQueryClient.class)
    public ExtraQueryClient httpExtraQueryClient(RestClient.Builder builder,
                                                 @Value("${stockit.services.extra-url}") String extraUrl) {
        return new HttpExtraQueryClient(InternalRestClients.create(builder, extraUrl));
    }
}
