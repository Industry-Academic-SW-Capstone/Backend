package grit.launch.market;

import grit.stockIt.contract.ExchangeQueryClient;
import grit.stockIt.global.client.HttpExchangeQueryClient;
import grit.stockIt.testsupport.ServiceContextTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 시세 서비스가 다른 서비스 없이 혼자 뜬다 — 빠진 빈이 있으면 컨텍스트가 뜨지 않아 여기서 실패한다.
@SpringBootTest(classes = MarketApplication.class)
@ActiveProfiles("market")
@DisplayName("시세 서비스 단독 기동")
class MarketApplicationContextTest extends ServiceContextTestSupport {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("거래소 조회는 HTTP 클라이언트로 묻고, 다른 서비스의 빈은 없다")
    void startsAloneWithHttpExchangeClient() {
        assertThat(context.getBean(ExchangeQueryClient.class)).isInstanceOf(HttpExchangeQueryClient.class);
        assertThat(context.containsBean("matchingCommandConsumer")).isFalse();
        assertThat(context.containsBean("executionReportConsumer")).isFalse();
        assertThat(context.containsBean("missionEventConsumer")).isFalse();
    }

    @Test
    @DisplayName("자기 schema(market)의 표만 본다")
    void usesOwnSchema() {
        assertUsesOwnSchema("market", "outbox_message");
    }
}
