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
    @Column(name = "stock_code", length = 9)
    private String code; // 종목 코드

    @Column(name = "stock_name", nullable = false, length = 100)
    private String name; // 종목명

    @Column(name = "market_type", length = 10) // 예: "KOSPI", "KOSDAQ"
    private String marketType; // 시장 구분

    @Column(name = "listing_date")
    private LocalDate listingDate; // 상장일

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "industry_code")
    private Industry industry; // 산업 (FK)

    @Builder
    private Stock(String code, String name, String marketType, LocalDate listingDate, Industry industry){
        this.code = code;
        this.name = name;
        this.marketType = marketType;
        this.listingDate = listingDate;
        this.industry = industry;
    }

    // 정보 업데이트
    public void updateInfo(String name, String marketType, LocalDate listingDate, Industry industry) {
        this.name = name;
        this.marketType = marketType;
        this.listingDate = listingDate;
        this.industry = industry;
    }
}