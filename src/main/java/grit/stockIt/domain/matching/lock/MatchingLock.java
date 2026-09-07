package grit.stockIt.domain.matching.lock;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 종목별 매칭 직렬화 락 포트.
 *
 * <p>체결 이벤트는 유한한 수량을 가격 우선·시간 우선으로 배분하는 단위이므로,
 * 같은 종목의 이벤트는 반드시 직렬 처리해야 한다. 병렬 처리하면 후순위 주문이
 * 선순위보다 먼저 체결되어 우선순위 원칙이 깨진다. 이 락은 성능 제약이 아니라
 * 정확성 요구사항이며, 다른 종목끼리는 병렬 처리된다.
 *
 * <p>구현 선택은 {@code matching.orderbook.backend} 프로퍼티(redis|jpa)를 따른다.
 */
public interface MatchingLock {

    /**
     * 종목 락을 획득하면 {@code action}을 실행하고 그 결과를 반환한다.
     * 락을 얻지 못하면 {@code action}을 실행하지 않고 {@link Optional#empty()}를 반환한다.
     */
    <T> Optional<T> tryRun(String stockCode, Supplier<T> action);
}
