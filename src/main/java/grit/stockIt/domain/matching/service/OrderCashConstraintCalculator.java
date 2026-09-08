package grit.stockIt.domain.matching.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 계좌 현금 잔고 대비 실제로 체결 가능한 수량을 계산하는 순수 로직.
 * 협력자가 없으며 상태를 갖지 않는다(무-인자 생성자).
 */
public class OrderCashConstraintCalculator {

    /**
     * 주어진 현금으로 desired 수량까지 체결 가능한 실제 수량을 계산한다.
     *
     * @param cash    사용 가능한 현금 잔고
     * @param price   단가
     * @param desired 원하는 체결 수량
     * @return 실제로 감당 가능한 체결 수량 (0 이상, desired 이하)
     */
    public int calculate(BigDecimal cash, BigDecimal price, int desired) {
        if (price == null || price.signum() <= 0) {
            return 0;
        }
        if (cash.compareTo(price) < 0) {
            return 0;
        }
        BigDecimal affordableRaw = cash.divide(price, 0, RoundingMode.FLOOR);
        if (affordableRaw.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        int affordable = affordableRaw.min(BigDecimal.valueOf(desired)).intValue();
        return Math.min(affordable, desired);
    }
}
