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

// 종목 단위로 체결을 직렬화한다.
//
// 체결은 오더북을 읽고 수량을 배분한 뒤 주문·계좌를 쓰는 read-modify-write다. 같은 종목의 두
// 이벤트가 동시에 실행되면 같은 주문에 같은 수량이 두 번 배분되어 초과 체결이 난다.
// 종목이 다르면 락 키가 다르므로 서로 막지 않는다.
//
// advisory 락을 쓰는 이유: 잠그려는 대상이 특정 행이 아니라 "이 종목의 매칭 임계 구역"이다.
// 종목 마스터 행에 FOR UPDATE를 걸어도 같은 일을 하지만 그 행에 WAL이 쌓이고 VACUUM 대상이 되며
// 조회 기능과 핫스팟을 공유한다. advisory 락은 공유 메모리의 해시 엔트리 하나라 디스크에 아무것도
// 쓰지 않고, 트랜잭션 스코프라 커밋·롤백 시 자동 해제되어 해제 누락이나 중복 처리 창이 없다.
//
// 락을 기다린다. 이벤트를 보관할 큐가 없어 포기하면 체결됐어야 할 주문이 사라지기 때문이다.
// 대신 대기 중에도 DB 커넥션을 쥐고 있다(advisory 락 대기는 커넥션 위에서 일어난다).
// 유입이 처리 속도를 넘으면 커넥션 풀이 먼저 고갈되고 매칭과 무관한 API까지 함께 막힌다.
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
        // pg_advisory_xact_lock 은 기본이 무한 대기라 상한을 걸어야 한다.
        // SET LOCAL 은 바인드 파라미터를 못 받으므로 set_config(…, true)를 쓴다(세 번째 인자가 LOCAL).
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', :timeout, true)")
                .setParameter("timeout", lockTimeout)
                .getSingleResult();

        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:classId, hashtext(:stockCode))")
                .setParameter("classId", MATCHING_LOCK_CLASS_ID)
                .setParameter("stockCode", stockCode)
                .getSingleResult();
    }
}
