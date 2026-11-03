package grit.launch.exchange;

import grit.stockIt.testsupport.ServiceContextTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 거래소 서비스가 다른 서비스 없이 혼자 뜬다 — 빠진 빈이 있으면 컨텍스트가 뜨지 않아 여기서 실패한다.
@SpringBootTest(classes = ExchangeApplication.class)
@ActiveProfiles("exchange")
@DisplayName("거래소 서비스 단독 기동")
class ExchangeApplicationContextTest extends ServiceContextTestSupport {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("열린 주문 조회에 내부 API 로 답하고, 다른 서비스의 빈은 없다")
    void startsAloneAndAnswersInternalQueries() {
        assertThat(context.containsBean("internalExchangeQueryController")).isTrue();
        assertThat(context.containsBean("matchingCommandConsumer")).isTrue();
        assertThat(context.containsBean("kafkaFillCommandPublisher")).isFalse();
        assertThat(context.containsBean("executionReportConsumer")).isFalse();
    }

    @Test
    @DisplayName("자기 schema(exchange)의 표만 본다")
    void usesOwnSchema() {
        assertUsesOwnSchema("exchange", "exchange_order");
    }
}
