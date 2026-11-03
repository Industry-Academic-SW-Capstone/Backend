package grit.launch.extra;

import grit.stockIt.contract.BrokerQueryClient;
import grit.stockIt.global.client.HttpBrokerQueryClient;
import grit.stockIt.testsupport.ServiceContextTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 부가 서비스가 다른 서비스 없이 혼자 뜬다 — 빠진 빈이 있으면 컨텍스트가 뜨지 않아 여기서 실패한다.
@SpringBootTest(classes = ExtraApplication.class)
@ActiveProfiles("extra")
@DisplayName("부가 서비스 단독 기동")
class ExtraApplicationContextTest extends ServiceContextTestSupport {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("증권사 조회는 HTTP 클라이언트로 묻고, 부가 조회에는 내부 API 로 답한다")
    void startsAloneWithHttpBrokerClient() {
        assertThat(context.getBean(BrokerQueryClient.class)).isInstanceOf(HttpBrokerQueryClient.class);
        assertThat(context.containsBean("internalExtraQueryController")).isTrue();
        assertThat(context.containsBean("executionReportConsumer")).isFalse();
        assertThat(context.containsBean("matchingCommandConsumer")).isFalse();
    }

    @Test
    @DisplayName("자기 schema(extra)의 표만 본다")
    void usesOwnSchema() {
        assertUsesOwnSchema("extra", "mission_progress");
    }
}
