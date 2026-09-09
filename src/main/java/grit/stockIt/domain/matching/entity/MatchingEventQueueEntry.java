package grit.stockIt.domain.matching.entity;

import grit.stockIt.domain.order.entity.OrderMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RDB 이벤트 큐의 행. Redis List를 대체한다.
 *
 * <p>{@code seqNo}가 큐 순서를 정한다. 일반 유입은 해당 종목의 최댓값+1(뒤에 추가),
 * 잔여분 되돌리기는 최솟값-1(앞에 추가)로 넣는다. 동시 유입으로 seqNo가 겹치면
 * {@code id}가 타이브레이커가 된다.
 *
 * <p>실제 조회·삭제는 {@code FOR UPDATE SKIP LOCKED}가 필요해
 * {@code JpaMatchingEventQueue}가 네이티브 쿼리로 수행한다. 이 엔티티는 스키마 정의와
 * 테스트 환경(ddl-auto)의 테이블 생성을 담당한다.
 *
 * <p><b>인덱스는 여기 선언하지 않는다.</b> {@code @Index}를 두면 {@code ddl-auto=update}
 * 환경에서 앱이 뜰 때마다 재생성되어, 벤치마크로 인덱스를 제거한 상태를 유지할 수 없다.
 * {@code trade_order}의 오더북 인덱스와 동작을 맞추기 위해 마이그레이션
 * ({@code V11__add_matching_event_queue.sql})에서만 생성한다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "matching_event_queue")
public class MatchingEventQueueEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "stock_code", length = 20, nullable = false)
    private String stockCode;

    @Column(name = "seq_no", nullable = false)
    private Long seqNo;

    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_method", length = 20, nullable = false)
    private OrderMethod orderMethod;

    @Column(name = "price", precision = 19, scale = 2, nullable = false)
    private BigDecimal price;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "event_timestamp", nullable = false)
    private long eventTimestamp;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
