package grit.stockIt.domain.contest.entity;

import grit.stockIt.domain.contest.dto.ContestUpdateRequest;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.global.common.BaseEntity;

@Entity
@Table(name = "contest")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contest extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "contest_id")
    private Long contestId;

    @Column(name = "contest_name", nullable = false, length = 500)
    private String contestName;

    @Column(name = "is_default", nullable = false)
    @Builder.Default
    private Boolean isDefault = false;

    @Column(name = "manager_member_id")
    private Long managerMemberId;

    @Column(name = "start_date", nullable = false)
    private LocalDateTime startDate;

    @Column(name = "end_date")
    private LocalDateTime endDate;

    @Column(name = "seed_money", nullable = false)
    private Long seedMoney;

    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal commissionRate;

    @Column(name = "min_market_cap")
    private Long minMarketCap;

    @Column(name = "max_market_cap")
    private Long maxMarketCap;

    @Column(name = "daily_trade_limit")
    private Integer dailyTradeLimit;

    @Column(name = "max_holdings_count")
    private Integer maxHoldingsCount;

    @Column(name = "buy_cooldown_minutes")
    private Integer buyCooldownMinutes;

    @Column(name = "sell_cooldown_minutes")
    private Integer sellCooldownMinutes;

    @Column(name = "contest_note", columnDefinition = "text")
    private String contestNote;

    @Column(name = "password")
    private String password;

    @OneToMany(mappedBy = "contest", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Account> accounts = new ArrayList<>();

    /**
     * 대회 정보 업데이트 (null이 아닌 필드만)
     */
    public void update(ContestUpdateRequest request) {
        if (request.getContestName() != null) {
            this.contestName = request.getContestName();
        }
        if (request.getStartDate() != null) {
            this.startDate = request.getStartDate();
        }
        if (request.getEndDate() != null) {
            this.endDate = request.getEndDate();
        }
        if (request.getSeedMoney() != null) {
            this.seedMoney = request.getSeedMoney();
        }
        if (request.getCommissionRate() != null) {
            this.commissionRate = request.getCommissionRate();
        }
        if (request.getMinMarketCap() != null) {
            this.minMarketCap = request.getMinMarketCap();
        }
        if (request.getMaxMarketCap() != null) {
            this.maxMarketCap = request.getMaxMarketCap();
        }
        if (request.getDailyTradeLimit() != null) {
            this.dailyTradeLimit = request.getDailyTradeLimit();
        }
        if (request.getMaxHoldingsCount() != null) {
            this.maxHoldingsCount = request.getMaxHoldingsCount();
        }
        if (request.getBuyCooldownMinutes() != null) {
            this.buyCooldownMinutes = request.getBuyCooldownMinutes();
        }
        if (request.getSellCooldownMinutes() != null) {
            this.sellCooldownMinutes = request.getSellCooldownMinutes();
        }
        if (request.getContestNote() != null) {
            this.contestNote = request.getContestNote();
        }
        if (request.getPassword() != null) {
            this.password = request.getPassword().isBlank() ? null : request.getPassword();
        }
    }
}