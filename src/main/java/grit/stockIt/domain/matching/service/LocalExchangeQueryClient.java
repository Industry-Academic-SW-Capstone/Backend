package grit.stockIt.domain.matching.service;

import grit.stockIt.contract.ExchangeQueryClient;
import grit.stockIt.domain.matching.entity.ExchangeOrderStatus;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

// 거래소가 시세의 드문 조회에 답한다. 지금은 같은 프로세스의 메서드 호출이고, 서비스를 나누면 이 내용이 HTTP 엔드포인트가 된다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocalExchangeQueryClient implements ExchangeQueryClient {

    private final ExchangeOrderRepository exchangeOrderRepository;

    @Override
    public List<OpenOrder> openOrders() {
        return exchangeOrderRepository.findOrdersByStatus(ExchangeOrderStatus.OPEN);
    }
}
