package grit.stockIt.domain.matching.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 거래소의 체결 원장. 체결 번호(fillId)는 여기서 붙고, 통보로 증권사에 건너가 증권사 체결 내역의 번호가 된다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "exchange_fill")
public class ExchangeFill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "fill_id")
    private Long fillId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "stock_code", nullable = false, length = 20)
    private String stockCode;

    @Column(name = "price", nullable = false, precision = 19, scale = 2)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "filled_at", nullable = false)
    private LocalDateTime filledAt;

    public static ExchangeFill of(ExchangeOrder order, BigDecimal price, int quantity) {
        ExchangeFill fill = new ExchangeFill();
        fill.orderId = order.getOrderId();
        fill.stockCode = order.getStockCode();
        fill.price = price;
        fill.quantity = quantity;
        fill.filledAt = LocalDateTime.now();
        return fill;
    }
}
