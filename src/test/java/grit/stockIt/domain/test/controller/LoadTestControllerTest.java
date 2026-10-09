package grit.stockIt.domain.test.controller;

import grit.stockIt.domain.matching.service.FillDispatchResult;
import grit.stockIt.domain.order.entity.OrderMethod;
import grit.stockIt.domain.test.dto.MockExecutionAcceptedResponse;
import grit.stockIt.domain.test.dto.MockExecutionRequest;
import grit.stockIt.domain.test.dto.MockExecutionResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LoadTestController 응답 코드")
class LoadTestControllerTest {

    private static final MockExecutionRequest REQUEST = new MockExecutionRequest(
            "005930", "evt-1", OrderMethod.BUY, new BigDecimal("200"), 1, 1_700_000_000_000L);

    @Test
    @DisplayName("바로 처리했으면 200과 체결 주문 수")
    void processed_returns200() {
        ResponseEntity<?> response = controllerReturning(new FillDispatchResult.Processed(3))
                .injectMockExecution(REQUEST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((MockExecutionResponse) response.getBody()).filled()).isEqualTo(3);
    }

    @Test
    @DisplayName("큐에 넣었으면 202와 기록 위치")
    void queued_returns202() {
        ResponseEntity<?> response = controllerReturning(new FillDispatchResult.Queued(5, 99L))
                .injectMockExecution(REQUEST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isEqualTo(new MockExecutionAcceptedResponse("evt-1", 5, 99L));
    }

    @Test
    @DisplayName("처리도 큐 기록도 못 했으면 503")
    void failed_returns503() {
        ResponseEntity<?> response = controllerReturning(new FillDispatchResult.Failed())
                .injectMockExecution(REQUEST);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private LoadTestController controllerReturning(FillDispatchResult result) {
        return new LoadTestController((stockCode, event) -> result);
    }
}
