package grit.stockIt.domain.order.repository;

// 만료 명령을 넣는 데 필요한 것만. 종목 코드는 파티션 키다.
public record ExpirableMarketOrder(Long orderId, String stockCode) {
}
