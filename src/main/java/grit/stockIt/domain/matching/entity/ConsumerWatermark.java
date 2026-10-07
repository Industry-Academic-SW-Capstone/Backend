package grit.stockIt.domain.matching.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// 쓰기는 ConsumerWatermarkRepository#advance 의 조건부 upsert 로만 한다. 엔티티는 조회와 스키마용이다.
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "consumer_watermark")
public class ConsumerWatermark {

    @EmbeddedId
    private ConsumerWatermarkId id;

    @Column(name = "last_offset", nullable = false)
    private long lastOffset;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
