package grit.stockIt.domain.order.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.Session;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;

// 주문 하나의 홀딩은 한 번만 푼다. 같은 취소·만료 통보가 다시 와도(최소 한 번 전달) 0행이 돌아와 건너뛴다.
// 예외 대신 반환 행 수로 거르므로 중복이 와도 트랜잭션이 깨지지 않는다.
@Repository
@RequiredArgsConstructor
public class HoldReleaseWriter {

    private static final String MARK_SQL =
            "INSERT INTO hold_release (order_id, created_at) VALUES (?, now()) ON CONFLICT (order_id) DO NOTHING";

    private final EntityManager entityManager;

    public boolean markReleased(Long orderId) {
        return entityManager.unwrap(Session.class).doReturningWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(MARK_SQL)) {
                statement.setLong(1, orderId);
                return statement.executeUpdate() > 0;
            }
        });
    }
}
