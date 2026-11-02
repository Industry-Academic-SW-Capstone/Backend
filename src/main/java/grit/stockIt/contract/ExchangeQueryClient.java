package grit.stockIt.contract;

import java.util.List;

// 시세가 거래소에 지금 답을 받아야 하는 드문 조회. 지금은 거래소 구현을 메서드로 부르고, 서비스를 나누면(8단계)
// 구현만 HTTP 클라이언트로 바꾼다.
public interface ExchangeQueryClient {

    // 오더북에 열려 있는 주문. 시세가 기동할 때 구독할 종목을 되살린다 — 열린 주문 목록은 메모리에만 있다.
    List<OpenOrder> openOrders();

    record OpenOrder(Long orderId, String stockCode) {
    }
}
