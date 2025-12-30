package grit.stockIt.domain.stock.entity;

import grit.stockIt.domain.industry.entity.Industry;
import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;

@Entity
@Table(name = "stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE stock SET updated_at = NOW(), deleted_at = NOW() WHERE stock_code = ?")
@SQLRestriction("deleted_at IS NULL")
public class Stock extends BaseEntity {

    @Id
    @Column(name = "stock_code", length = 20)
    private String code; // 종목 코드

    @Column(name = "stock_name", nullable = false, length = 50)
    private String name; // 종목명

    @Column(name = "market_type", length = 10) // KOSPI, KOSDAQ
    private String marketType; // 시장 구분

    @Column(name = "listing_date")
    private LocalDate listingDate; // 상장일

    @Column(name = "industry_code", length = 20)
    private String industryCode; // 업종코드 (FK)

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "industry_code", insertable = false, updatable = false)
    private Industry industry; // 업종 엔티티

    @Builder
    private Stock(String code, String name, String marketType, LocalDate listingDate, String industryCode) {
        this.code = code;
        this.name = name;
        this.marketType = marketType;
        this.listingDate = listingDate;
        this.industryCode = industryCode;
    }

    public void updateInfo(String name, String marketType, LocalDate listingDate, String industryCode) {
        // 각 필드에 대한 유효성 검사 호출 또는 직접 수행
        updateName(name); // 기존 메서드 재사용
        updateMarketType(marketType); // 기존 메서드 재사용
        updateIndustryCode(industryCode); // 기존 메서드 재사용 (null 허용 시 검사 불필요)
        updateListingDate(listingDate); // 상장일 업데이트 메서드 추가 필요
    }

    public void updateListingDate(LocalDate listingDate) {
        // 필요하다면 날짜 유효성 검사 추가 (예: 너무 과거/미래 날짜 방지)
        this.listingDate = listingDate;
    }

    // 종목명 업데이트 메서드
    public void updateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("종목명은 비어있거나 공백만 있을 수 없습니다.");
        }
        this.name = name;
    }

    // 업종코드 업데이트 메서드
    public void updateIndustryCode(String industryCode) {
        this.industryCode = industryCode;
    }

    // 시장구분 업데이트 메서드
    public void updateMarketType(String marketType) {
        if (marketType == null || marketType.trim().isEmpty()) {
            throw new IllegalArgumentException("시장구분은 비어있거나 공백만 있을 수 없습니다.");
        }
        this.marketType = marketType;
    }
}