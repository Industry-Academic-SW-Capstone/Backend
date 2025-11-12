package grit.stockIt.domain.account.entity;

import grit.stockIt.domain.stock.entity.Stock;
import grit.stockIt.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "account_stock",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_account_stock_account_stock",
                columnNames = {"account_id", "stock_code"}
        ))
@SQLDelete(sql = "UPDATE account_stock SET updated_at = NOW(), deleted_at = NOW() WHERE account_stock_id = ?")
@SQLRestriction("deleted_at IS NULL")
public class AccountStock extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_stock_id")
    private Long accountStockId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_code", nullable = false)
    private Stock stock;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "hold_quantity", nullable = false)
    private int holdQuantity;

    @Column(name = "average_price", precision = 19, scale = 2, nullable = false)
    private BigDecimal averagePrice;

    private AccountStock(Account account, Stock stock, int initialQuantity, BigDecimal initialPrice) {
        validateAccount(account);
        validateStock(stock);
        ensurePositiveQuantity(initialQuantity, "초기 보유 수량");
        ensurePositivePrice(initialPrice);
        this.account = account;
        this.stock = stock;
        this.quantity = initialQuantity;
        this.averagePrice = initialPrice;
    }

    public static AccountStock create(Account account, Stock stock, int initialQuantity, BigDecimal initialPrice) {
        return new AccountStock(account, stock, initialQuantity, initialPrice);
    }

    public int getAvailableQuantity() {
        return this.quantity - this.holdQuantity;
    }

    public void increaseQuantity(int addQuantity, BigDecimal tradePrice) {
        ensurePositiveQuantity(addQuantity, "추가 수량");
        ensurePositivePrice(tradePrice);

        BigDecimal currentValue = this.averagePrice.multiply(BigDecimal.valueOf(this.quantity));
        BigDecimal addedValue = tradePrice.multiply(BigDecimal.valueOf(addQuantity));

        this.quantity += addQuantity;
        this.averagePrice = currentValue.add(addedValue)
                .divide(BigDecimal.valueOf(this.quantity), 2, RoundingMode.HALF_UP);
    }

    public void decreaseQuantity(int reduceQuantity) {
        ensurePositiveQuantity(reduceQuantity, "차감 수량");
        int availableQuantity = getAvailableQuantity();
        if (reduceQuantity > availableQuantity) {
            throw new IllegalStateException("사용 가능한 수량보다 많은 수량을 차감할 수 없습니다.");
        }
        this.quantity -= reduceQuantity;
        if (this.quantity == 0) {
            softDelete();
            this.averagePrice = BigDecimal.ZERO;
        }
    }

    public void increaseHoldQuantity(int addQuantity) {
        ensurePositiveQuantity(addQuantity, "홀딩 수량");
        if (addQuantity > getAvailableQuantity()) {
            throw new IllegalStateException("보유 수량보다 많은 수량을 홀딩할 수 없습니다.");
        }
        this.holdQuantity += addQuantity;
    }

    public void decreaseHoldQuantity(int reduceQuantity) {
        ensurePositiveQuantity(reduceQuantity, "홀딩 해제 수량");
        if (reduceQuantity > this.holdQuantity) {
            throw new IllegalStateException("홀딩된 수량보다 많은 수량을 해제할 수 없습니다.");
        }
        this.holdQuantity -= reduceQuantity;
    }

    public void reactivate(int newQuantity, BigDecimal newPrice) {
        ensurePositiveQuantity(newQuantity, "재활성화 수량");
        ensurePositivePrice(newPrice);
        restore();
        this.quantity = newQuantity;
        this.holdQuantity = 0;
        this.averagePrice = newPrice;
    }

    private void ensurePositiveQuantity(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + "은(는) 0보다 커야 합니다.");
        }
    }

    private void ensurePositivePrice(BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("거래 가격은 0보다 커야 합니다.");
        }
    }

    private void validateAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("계좌 정보가 필요합니다.");
        }
    }

    private void validateStock(Stock stock) {
        if (stock == null) {
            throw new IllegalArgumentException("종목 정보가 필요합니다.");
        }
    }
}

