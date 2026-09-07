package grit.stockIt.domain.matching.lock;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * PostgreSQL advisory lock 기반 종목별 직렬화 락 (RDB 오더북 전용).
 *
 * <p>Redis 분산 락과 달리 트랜잭션 스코프 락({@code pg_try_advisory_xact_lock})을 쓰므로
 * 커밋·롤백 시점에 자동 해제된다. 그 결과 TTL 튜닝, 해제 실패, 락 해제와 커밋 사이의
 * 시차로 인한 중복 처리 창(window)이 모두 사라진다. 오더북과 락이 같은 트랜잭션 안에
 * 있으므로 이중 쓰기 정합성 문제 자체가 성립하지 않는다.
 *
 * <p>{@code @Transactional}이 여기서 트랜잭션을 열고,
 * {@code LimitOrderExecutionService#distributeEvent}(REQUIRED)가 여기에 참여한다.
 * 락은 그 트랜잭션 전체 구간에서 유지된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "matching.orderbook.backend", havingValue = "jpa")
public class AdvisoryMatchingLock implements MatchingLock {

    /** advisory lock 네임스페이스. 다른 용도의 advisory lock과 키가 겹치지 않게 한다. */
    private static final int MATCHING_LOCK_CLASS_ID = 1001;

    private final EntityManager entityManager;

    @Override
    @Transactional
    public <T> Optional<T> tryRun(String stockCode, Supplier<T> action) {
        Object acquired = entityManager
                .createNativeQuery("SELECT pg_try_advisory_xact_lock(:classId, hashtext(:stockCode))")
                .setParameter("classId", MATCHING_LOCK_CLASS_ID)
                .setParameter("stockCode", stockCode)
                .getSingleResult();

        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("지정가 매칭 advisory 락 획득 실패. stockCode={}", stockCode);
            return Optional.empty();
        }

        return Optional.ofNullable(action.get());
    }
}
