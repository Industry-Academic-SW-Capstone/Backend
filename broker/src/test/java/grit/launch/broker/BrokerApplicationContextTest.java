package grit.launch.broker;

import grit.stockIt.contract.ExtraQueryClient;
import grit.stockIt.contract.MarketPriceCache;
import grit.stockIt.global.client.HttpExtraQueryClient;
import grit.stockIt.testsupport.ServiceContextTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 증권사 서비스가 다른 서비스 없이 혼자 뜬다 — 빠진 빈이 있으면 컨텍스트가 뜨지 않아 여기서 실패한다.
@SpringBootTest(classes = BrokerApplication.class)
@ActiveProfiles("broker")
@DisplayName("증권사 서비스 단독 기동")
class BrokerApplicationContextTest extends ServiceContextTestSupport {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("부가 조회는 HTTP 클라이언트로 묻고, 증권사 조회에는 내부 API 로 답한다")
    void startsAloneWithHttpExtraClient() {
        assertThat(context.getBean(ExtraQueryClient.class)).isInstanceOf(HttpExtraQueryClient.class);
        assertThat(context.getBean(MarketPriceCache.class)).isNotNull();
        assertThat(context.containsBean("internalBrokerQueryController")).isTrue();
        assertThat(context.containsBean("missionEventConsumer")).isFalse();
        assertThat(context.containsBean("matchingCommandConsumer")).isFalse();
    }
}
