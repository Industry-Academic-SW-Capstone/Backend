package grit.stockIt.domain.title.entity;

import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_title",
        // 한 명의 유저가 동일한 칭호를 여러 번 획득하는 것을 방지
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "member_title_uk",
                        columnNames = {"member_id", "title_id"}
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberTitle extends BaseEntity { // 생성(획득) 시간은 BaseEntity의 createdAt 사용

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "member_title_id")
    private Long id;

    // 회원은 증권사가 가진다. 부가는 번호로만 가리킨다.
    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "title_id", nullable = false)
    private Title title;

    // (필요시 '대표 칭호' 여부 등을 boolean 필드로 추가할 수 있습니다)
    // @Column(name = "is_representative", nullable = false)
    // private boolean isRepresentative = false;

    @Builder
    public MemberTitle(Long memberId, Title title) {
        this.memberId = memberId;
        this.title = title;
    }

}