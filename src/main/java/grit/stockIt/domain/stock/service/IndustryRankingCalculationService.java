package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.industry.entity.Industry;
import grit.stockIt.domain.stock.dto.IndustryStockRankingResponse;
import grit.stockIt.domain.stock.dto.StockRankingResponse;
import grit.stockIt.domain.stock.entity.Stock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 업종별 인기 종목 랭킹 계산 (의존 0).
 *
 * 업종 랭킹 규칙(업종 정렬 기준, 업종당 상한, 동점 처리)이 바뀔 때 이 클래스가 바뀐다.
 *
 * 두 단계로 나뉜 이유는 그 사이에 DB 조회가 끼기 때문이다. 호출자는 {@link #groupAndSort}가
 * 돌려준 업종코드로 Industry를 조회한 뒤 그 결과를 {@link #selectTopStocks}에 넘긴다.
 */
@Slf4j
@Service
public class IndustryRankingCalculationService {

    /**
     * 그룹핑 결과와 업종 정렬 순서를 함께 나른다.
     *
     * 방어적 복사를 하지 않는다. stocksByIndustry는 groupingBy가 만든 HashMap 그대로여야
     * 하며, 복사하면 거래대금 합계가 같은 업종들의 순회 순서가 달라질 수 있다.
     */
    public record IndustryGrouping(
            Map<String, List<StockRankingResponse>> stocksByIndustry,
            List<String> sortedIndustryCodes
    ) {
    }

    /**
     * 업종코드로 그룹핑한 뒤 업종별 거래대금 합계 내림차순으로 정렬한다.
     *
     * 반환된 sortedIndustryCodes는 순서를 바꾸지 말고 그대로 Industry 조회에 사용해야 한다.
     * 합계가 같은 업종끼리의 순서는 정렬이 아니라 HashMap 순회 순서가 결정하므로,
     * 재정렬·중복 제거·필터를 거치면 현재 응답 순서가 깨진다.
     */
    public IndustryGrouping groupAndSort(List<StockRankingResponse> allStocks, Map<String, Stock> stockMap) {
        Map<String, List<StockRankingResponse>> stocksByIndustry = allStocks.stream()
                .filter(stock -> {
                    Stock stockEntity = stockMap.get(stock.stockCode());
                    return stockEntity != null && stockEntity.getIndustryCode() != null;
                })
                .collect(Collectors.groupingBy(
                        stock -> {
                            Stock stockEntity = stockMap.get(stock.stockCode());
                            return stockEntity != null ? stockEntity.getIndustryCode() : "UNKNOWN";
                        }
                ));

        stocksByIndustry.remove("UNKNOWN");

        List<String> sortedIndustryCodes = stocksByIndustry.entrySet().stream()
                .sorted((e1, e2) -> {
                    long sum1 = e1.getValue().stream()
                            .mapToLong(StockRankingResponse::amount)
                            .sum();
                    long sum2 = e2.getValue().stream()
                            .mapToLong(StockRankingResponse::amount)
                            .sum();
                    return Long.compare(sum2, sum1); // 내림차순
                })
                .map(Map.Entry::getKey)
                .toList();

        return new IndustryGrouping(stocksByIndustry, sortedIndustryCodes);
    }

    /**
     * 업종 순서대로 각 업종의 거래대금 상위 종목을 골라 응답을 조립한다.
     *
     * industryMap에 없는 업종코드는 industryName이 null인 채로 나간다.
     */
    public List<IndustryStockRankingResponse> selectTopStocks(
            Map<String, List<StockRankingResponse>> stocksByIndustry,
            List<String> sortedIndustryCodes,
            Map<String, Industry> industryMap,
            int maxPerIndustry) {

        List<IndustryStockRankingResponse> result = new ArrayList<>();

        for (String industryCode : sortedIndustryCodes) {
            List<StockRankingResponse> stocks = stocksByIndustry.get(industryCode);

            List<StockRankingResponse> topStocks = stocks.stream()
                    .sorted((a, b) -> Long.compare(b.amount(), a.amount()))
                    .limit(maxPerIndustry)
                    .toList();

            if (!topStocks.isEmpty()) {
                Industry industry = industryMap.get(industryCode);
                String industryName = industry != null ? industry.getName() : null;

                result.add(new IndustryStockRankingResponse(
                        industryCode,
                        industryName,
                        topStocks
                ));
            }
        }

        return result;
    }
}
