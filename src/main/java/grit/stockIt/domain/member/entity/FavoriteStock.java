package grit.stockIt.domain.member.entity;

import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "favorite_stock", uniqueConstraints = {@UniqueConstraint(columnNames = {"member_id", "stock_code"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class FavoriteStock extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "favorite_stock_id")
    private Long favoriteStockId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    // Stock primary key in this project is 'stock_code' (String)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_code", referencedColumnName = "stock_code", nullable = false)
    private Stock stock;

    public static FavoriteStock of(Member member, Stock stock) {
        return FavoriteStock.builder()
                .member(member)
                .stock(stock)
                .build();
    }

    public void detach() {
        this.member = null;
        this.stock = null;
    }
}
