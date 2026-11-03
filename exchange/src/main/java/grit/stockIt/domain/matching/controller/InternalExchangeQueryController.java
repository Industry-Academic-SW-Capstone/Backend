package grit.stockIt.domain.matching.controller;

import grit.stockIt.contract.ExchangeQueryApi;
import grit.stockIt.contract.ExchangeQueryClient;
import grit.stockIt.domain.matching.service.LocalExchangeQueryClient;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 시세의 기동 시 복구 조회(ExchangeQueryClient)에 HTTP 로 답한다. 내부망 전용.
@Hidden
@RestController
@RequestMapping(ExchangeQueryApi.BASE)
@ConditionalOnProperty(name = "stockit.internal-api.enabled", havingValue = "true")
@RequiredArgsConstructor
public class InternalExchangeQueryController {

    private final LocalExchangeQueryClient exchangeQueryClient;

    @GetMapping(ExchangeQueryApi.OPEN_ORDERS)
    public List<ExchangeQueryClient.OpenOrder> openOrders() {
        return exchangeQueryClient.openOrders();
    }
}
