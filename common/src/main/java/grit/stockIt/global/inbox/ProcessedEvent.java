package grit.stockIt.global.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

// 쓰기는 ProcessedEventWriter 의 INSERT ... ON CONFLICT 로만 한다. 엔티티는 스키마용이다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @EmbeddedId
    private Key id;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Getter
    @Embeddable
    @EqualsAndHashCode
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    public static class Key implements Serializable {

        @Column(name = "consumer", nullable = false, length = 50)
        private String consumer;

        @Column(name = "event_id", nullable = false, length = 64)
        private String eventId;
    }
}
