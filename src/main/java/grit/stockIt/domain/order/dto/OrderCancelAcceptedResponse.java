package grit.stockIt.domain.order.dto;

// 취소 접수 결과. 취소 여부는 워커가 처리한 뒤 주문 상태로 확인한다.
public record OrderCancelAcceptedResponse(Long orderId) {
}
