package grit.stockIt.domain.contest.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.global.common.BaseEntity; // 추가

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

    @OneToMany(mappedBy = "contest", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Account> accounts = new ArrayList<>();
}