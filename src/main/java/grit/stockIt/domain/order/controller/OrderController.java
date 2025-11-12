package grit.stockIt.domain.order.controller;

import grit.stockIt.domain.order.dto.LimitOrderCreateRequest;
import grit.stockIt.domain.order.dto.OrderResponse;
import grit.stockIt.domain.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/orders")
@Tag(name = "orders", description = "주문 관련 API")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Operation(summary = "지정가 주문 생성", description = "지정가 매수/매도 주문을 등록합니다.")
    @PostMapping("/limit")
    public OrderResponse createLimitOrder(@Valid @RequestBody LimitOrderCreateRequest request) {
        return orderService.createLimitOrder(request);
    }

    @Operation(summary = "주문 취소", description = "미체결 주문을 취소합니다.")
    @PostMapping("/{orderId}/cancel")
    public OrderResponse cancelOrder(@PathVariable Long orderId) {
        return orderService.cancelOrder(orderId);
    }

    @Operation(summary = "주문 조회", description = "주문 상세 정보를 조회합니다.")
    @GetMapping("/{orderId}")
    public OrderResponse getOrder(@PathVariable Long orderId) {
        return orderService.getOrder(orderId);
    }
}

