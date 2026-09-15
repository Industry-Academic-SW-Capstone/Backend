package grit.stockIt.domain.matching.service;

import grit.stockIt.domain.execution.entity.Execution;
import grit.stockIt.domain.matching.dto.LimitOrderFillEvent;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class LimitOrderMatchingService {

    // advisory 락 네임스페이스. 다른 용도의 advisory 락과 키가 겹치지 않게 한다.
    private static final int MATCHING_LOCK_CLASS_ID = 1001;

    private final LimitOrderExecutionService limitOrderExecutionService;
    private final EntityManager entityManager;

    @Value("${matching.lock.timeout:10s}")
    private String lockTimeout;

    // 종목 락을 잡고 체결 이벤트를 정산한다. 여기서 연 트랜잭션에 distributeEvent(REQUIRED)가
    // 참여하므로, 락은 정산 전 구간에서 유지되다 커밋 시 풀린다.
    // 락 대기가 matching.lock.timeout 을 넘기면 예외가 난다.
    @Transactional
    public List<Execution> match(String stockCode, LimitOrderFillEvent event) {
        acquireStockLock(stockCode);
        return limitOrderExecutionService.distributeEvent(stockCode, event);
    }

    private void acquireStockLock(String stockCode) {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", lockTimeout)
                .getSingleResult();

        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:classId, hashtext(:stockCode))")
                .setParameter("classId", MATCHING_LOCK_CLASS_ID)
                .setParameter("stockCode", stockCode)
                .getSingleResult();
    }
}
