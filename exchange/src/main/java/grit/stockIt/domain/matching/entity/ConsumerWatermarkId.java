package grit.stockIt.domain.matching.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Getter
@EqualsAndHashCode
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Embeddable
public class ConsumerWatermarkId implements Serializable {

    @Column(name = "topic", length = 200, nullable = false)
    private String topic;

    @Column(name = "partition_no", nullable = false)
    private int partitionNo;
}
