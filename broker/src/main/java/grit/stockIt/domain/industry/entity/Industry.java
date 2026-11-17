package grit.stockIt.domain.industry.entity;

import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "industry")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 삭제 요청이 발생했을 때, 실제 DELETE 쿼리 대신 지정된 UPDATE 쿼리를 실행하도록 JPA에게 지시
@SQLDelete(sql = "UPDATE industry SET updated_at = NOW(), deleted_at = NOW() WHERE industry_code = ?")
// 모든 SELECT 쿼리에 삭제되지 않은 데이터만 찾아줌.
@SQLRestriction("deleted_at IS NULL")
public class Industry extends BaseEntity {

    @Id // 기본 키 (Primary Key)
    @Column(name = "industry_code", length = 20) // DB 컬럼명 및 길이 일치
    private String code; // 업종 코드 (PK)

    @Column(name = "industry_name", nullable = true, length = 100) // 이름은 초기에 null일 수 있음
    private String name; // 업종명

    @Builder
    private Industry(String code, String name) {
        this.code = code;
        this.name = name;
    }

    // 업종명 업데이트 메서드
    public void updateName(String name) {
        if (name != null && name.trim().isEmpty()) {
            throw new IllegalArgumentException("산업명은 비어있거나 공백만 있을 수 없습니다.");
        }
        this.name = name;
    }
}