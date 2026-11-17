package grit.stockIt.domain.ranking.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.stock.entity.Stock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RankingCalculationService 순수 유닛 테스트 (의존 0, Spring 컨텍스트 없이 new)
 */
class RankingCalculationServiceTest {

    private final RankingCalculationService service = new RankingCalculationService();

    private Account account(BigDecimal cash) {
        return Account.builder()
                .cash(cash)
                .build();
    }

    private Stock stock(String code) {
        return Stock.builder()
                .code(code)
                .name("종목" + code)
                .marketType("KOSPI")
                .build();
    }

    private Contest contest(Long seedMoney) {
        return Contest.builder()
                .seedMoney(seedMoney)
                .build();
    }

    @Nested
    @DisplayName("calculateTotalAssets")
    class CalculateTotalAssets {

        @Test
        @DisplayName("보유 종목이 없으면 현금만 반환한다")
        void noHoldings() {
            Account acc = account(new BigDecimal("100000"));

            BigDecimal result = service.calculateTotalAssets(acc, Map.of(), Map.of());

            assertThat(result).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("보유 수량이 0 이하인 종목은 스킵한다")
        void skipsNonPositiveQuantity() {
            Account acc = account(new BigDecimal("100000"));
            Stock st = stock("005930");
            AccountStock holding = AccountStock.create(acc, st, 1, new BigDecimal("50000"));
            holding.decreaseQuantity(1); // quantity -> 0

            Map<Account, List<AccountStock>> accountStocksMap = Map.of(acc, List.of(holding));
            Map<String, BigDecimal> prices = Map.of("005930", new BigDecimal("60000"));

            BigDecimal result = service.calculateTotalAssets(acc, prices, accountStocksMap);

            assertThat(result).isEqualByComparingTo("100000");
        }

        @Test
        @DisplayName("현재가 Map에 없는 종목은 취득원가(averagePrice)로 폴백한다 (버그 a+h 수정)")
        void missingPriceFallsBackToAveragePrice() {
            Account acc = account(new BigDecimal("100000"));
            Stock st = stock("005930");
            AccountStock holding = AccountStock.create(acc, st, 10, new BigDecimal("50000"));

            Map<Account, List<AccountStock>> accountStocksMap = Map.of(acc, List.of(holding));

            BigDecimal result = service.calculateTotalAssets(acc, Map.of(), accountStocksMap);

            // 100000 + 10*50000(취득원가) = 600000
            assertThat(result).isEqualByComparingTo("600000");
        }

        @Test
        @DisplayName("현재가가 0 이하로 수신되면 취득원가(averagePrice)로 폴백한다 (버그 a+h 수정)")
        void zeroOrNegativePriceFallsBackToAveragePrice() {
            Account acc = account(new BigDecimal("100000"));
            Stock st = stock("005930");
            AccountStock holding = AccountStock.create(acc, st, 10, new BigDecimal("50000"));

            Map<Account, List<AccountStock>> accountStocksMap = Map.of(acc, List.of(holding));
            Map<String, BigDecimal> prices = Map.of("005930", BigDecimal.ZERO);

            BigDecimal result = service.calculateTotalAssets(acc, prices, accountStocksMap);

            // 100000 + 10*50000(취득원가) = 600000
            assertThat(result).isEqualByComparingTo("600000");
        }

        @Test
        @DisplayName("총자산 = 현금 + Σ(보유수량 × 현재가)")
        void cashPlusStockValue() {
            Account acc = account(new BigDecimal("100000"));
            Stock st1 = stock("005930");
            Stock st2 = stock("000660");
            AccountStock h1 = AccountStock.create(acc, st1, 10, new BigDecimal("50000"));
            AccountStock h2 = AccountStock.create(acc, st2, 5, new BigDecimal("100000"));

            Map<Account, List<AccountStock>> accountStocksMap = Map.of(acc, List.of(h1, h2));
            Map<String, BigDecimal> prices = Map.of(
                    "005930", new BigDecimal("60000"),
                    "000660", new BigDecimal("120000")
            );

            BigDecimal result = service.calculateTotalAssets(acc, prices, accountStocksMap);

            // 100000 + (10*60000) + (5*120000) = 100000 + 600000 + 600000 = 1300000
            assertThat(result).isEqualByComparingTo("1300000");
        }

        @Test
        @DisplayName("accountStocksMap에 자신의 계좌가 없으면 빈 리스트로 처리한다")
        void accountNotInMap() {
            Account acc = account(new BigDecimal("50000"));
            Account other = account(new BigDecimal("0"));

            BigDecimal result = service.calculateTotalAssets(acc, Map.of(), Map.of(other, Collections.emptyList()));

            assertThat(result).isEqualByComparingTo("50000");
        }
    }

    @Nested
    @DisplayName("calculateReturnRateFromAssets")
    class CalculateReturnRateFromAssets {

        @Test
        @DisplayName("contest가 null이면 0을 반환한다")
        void nullContest() {
            BigDecimal result = service.calculateReturnRateFromAssets(new BigDecimal("100000"), null);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("seedMoney가 null이면 0을 반환한다")
        void nullSeedMoney() {
            Contest c = contest(null);

            BigDecimal result = service.calculateReturnRateFromAssets(new BigDecimal("100000"), c);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("seedMoney가 0이면 0을 반환한다")
        void zeroSeedMoney() {
            Contest c = contest(0L);

            BigDecimal result = service.calculateReturnRateFromAssets(new BigDecimal("100000"), c);

            assertThat(result).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("(총자산-시드머니)/시드머니*100, HALF_UP scale 2")
        void computesReturnRateWithHalfUpScale2() {
            Contest c = contest(100000L);

            BigDecimal result = service.calculateReturnRateFromAssets(new BigDecimal("150000"), c);

            // (150000-100000)/100000*100 = 50.00
            assertThat(result).isEqualByComparingTo("50.00");
            assertThat(result.scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("assignCompetitionRanks")
    class AssignCompetitionRanks {

        @Test
        @DisplayName("빈 리스트는 빈 리스트를 반환한다")
        void emptyList() {
            List<Integer> ranks = service.assignCompetitionRanks(List.of());

            assertThat(ranks).isEmpty();
        }

        @Test
        @DisplayName("단일 원소는 순위 1을 받는다")
        void singleElement() {
            List<Integer> ranks = service.assignCompetitionRanks(List.of(new BigDecimal("100")));

            assertThat(ranks).containsExactly(1);
        }

        @Test
        @DisplayName("전부 동률이면 모두 순위 1")
        void allTied() {
            BigDecimal a = new BigDecimal("100");
            List<Integer> ranks = service.assignCompetitionRanks(List.of(a, a, a));

            assertThat(ranks).containsExactly(1, 1, 1);
        }

        @Test
        @DisplayName("2개 동률군 [a,a,b,b,c] -> [1,1,3,3,5]")
        void twoTiedGroups() {
            BigDecimal a = new BigDecimal("500");
            BigDecimal b = new BigDecimal("300");
            BigDecimal c = new BigDecimal("100");
            List<Integer> ranks = service.assignCompetitionRanks(List.of(a, a, b, b, c));

            assertThat(ranks).containsExactly(1, 1, 3, 3, 5);
        }

        @Test
        @DisplayName("전부 상이하면 순차 순위 1..N")
        void allDistinct() {
            List<Integer> ranks = service.assignCompetitionRanks(List.of(
                    new BigDecimal("500"), new BigDecimal("400"), new BigDecimal("300")
            ));

            assertThat(ranks).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("compareTo 기준 동률 판정 - scale이 달라도 값이 같으면 동률")
        void scaleDifferenceStillTied() {
            BigDecimal a = new BigDecimal("100.0");
            BigDecimal b = new BigDecimal("100.00");
            List<Integer> ranks = service.assignCompetitionRanks(List.of(a, b));

            assertThat(ranks).containsExactly(1, 1);
        }
    }
}
