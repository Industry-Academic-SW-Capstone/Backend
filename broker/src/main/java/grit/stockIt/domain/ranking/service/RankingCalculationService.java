package grit.stockIt.domain.ranking.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.entity.AccountStock;
import grit.stockIt.domain.contest.entity.Contest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 랭킹 순수 계산 로직 (의존 0)
 * - 총자산/수익률 계산
 * - 동률 순위 산정
 */
@Service
public class RankingCalculationService {

    /**
     * 총자산으로부터 수익률 계산
     */
    BigDecimal calculateReturnRateFromAssets(BigDecimal totalAssets, Contest contest) {
        if (contest == null || contest.getSeedMoney() == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal seedMoney = BigDecimal.valueOf(contest.getSeedMoney());
        if (seedMoney.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // (총자산 - 시드머니) / 시드머니 * 100
        BigDecimal profit = totalAssets.subtract(seedMoney);
        BigDecimal returnRate = profit.divide(seedMoney, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        return returnRate.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 계좌의 총자산 계산
     * 총자산 = 잔액 + Σ(보유수량 × 평가단가)
     * - 평가단가는 현재가를 우선 사용하되, 현재가가 미가용(null 또는 0 이하)이면
     *   취득원가(AccountStock.averagePrice)로 폴백한다. 주식은 0에 거래되지 않으므로
     *   0 이하는 안전하게 "가격 없음"으로 취급한다.
     *
     * @param account       계좌
     * @param currentPrices 종목코드별 현재가 Map
     * @param accountStocksMap Account별 AccountStock 리스트 Map
     * @return 총자산
     */
    BigDecimal calculateTotalAssets(Account account, Map<String, BigDecimal> currentPrices,
                                           Map<Account, List<AccountStock>> accountStocksMap) {
        BigDecimal cash = account.getCash();
        BigDecimal stockValue = BigDecimal.ZERO;

        List<AccountStock> holdings = accountStocksMap.getOrDefault(account, Collections.emptyList());
        for (AccountStock holding : holdings) {
            if (holding.getQuantity() <= 0) {
                continue;
            }

            String stockCode = holding.getStock().getCode();
            BigDecimal price = currentPrices.get(stockCode);
            if (price == null || price.signum() <= 0) {
                price = holding.getAveragePrice(); // 현재가 미가용 → 취득원가 폴백
            }
            BigDecimal value = price.multiply(BigDecimal.valueOf(holding.getQuantity()));
            stockValue = stockValue.add(value);
        }

        return cash.add(stockValue);
    }

    /**
     * 정렬된 값 리스트로부터 동률 처리 경쟁 순위(competition ranking) 산정
     * - 동일 값이면 동일 순위, 다음 순위는 동률 개수만큼 건너뜀
     * - 반드시 compareTo()==0 사용(BigDecimal.equals는 scale 오판 위험)
     *
     * @param sortedValues 이미 정렬(내림차순 등)된 값 리스트
     * @return 각 위치의 순위 리스트 (입력과 동일 순서)
     */
    List<Integer> assignCompetitionRanks(List<BigDecimal> sortedValues) {
        List<Integer> ranks = new ArrayList<>();
        int rank = 1;
        BigDecimal prevValue = null;
        int sameRankCount = 0;

        for (BigDecimal currentValue : sortedValues) {
            if (prevValue != null && prevValue.compareTo(currentValue) == 0) {
                sameRankCount++;
            } else {
                rank += sameRankCount;
                sameRankCount = 1;
            }

            ranks.add(rank);
            prevValue = currentValue;
        }

        return ranks;
    }
}
