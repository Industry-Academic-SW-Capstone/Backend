package grit.stockIt.domain.matching.lock;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 종목별 직렬화 락. 이 종목의 오더북을 바꾸는 경로는 모두 이 락을 잡아야 한다.
// 트랜잭션 스코프라 커밋·롤백 시 자동으로 풀린다.
@Component
@RequiredArgsConstructor
public class StockMatchingLock {

    // advisory 락 네임스페이스. 다른 용도의 advisory 락과 키가 겹치지 않게 한다.
    private static final int LOCK_CLASS_ID = 1001;

    private final EntityManager entityManager;

    @Value("${matching.lock.timeout:10s}")
    private String defaultTimeout;

    public void acquire(String stockCode) {
        acquire(stockCode, defaultTimeout);
    }

    // SET LOCAL 은 바인드 파라미터를 받지 못해 set_config 를 쓴다. 세 번째 인자가 트랜잭션 로컬 여부다.
    // advisory 락은 기본이 무한 대기라 타임아웃을 반드시 걸어야 한다.
    public void acquire(String stockCode, String timeout) {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", timeout)
                .getSingleResult();

        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:classId, hashtext(:stockCode))")
                .setParameter("classId", LOCK_CLASS_ID)
                .setParameter("stockCode", stockCode)
                .getSingleResult();
    }
}
