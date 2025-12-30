package grit.stockIt.domain.stock.service;

import grit.stockIt.domain.stock.dto.StockRankingDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
class StockRankingServiceTest {

    @Autowired
    private StockRankingService stockRankingService;

    @Test
    @DisplayName("거래대금 상위 종목 조회 - 20건 확인")
    void testAmountTopStocks() {
        // Given
        int expectedCount = 20;

        // When
        List<StockRankingDto> stocks = stockRankingService.getAmountTopStocks(expectedCount).block();

        // Then
        assertNotNull(stocks, "조회된 종목 리스트는 null이 아니어야 함");
        assertEquals(expectedCount, stocks.size(), "조회된 종목 수는 " + expectedCount + "개여야 함");

        // 첫 번째 종목 검증
        StockRankingDto firstStock = stocks.get(0);
        assertNotNull(firstStock.stockCode(), "종목코드는 null이 아니어야 함");
        assertNotNull(firstStock.stockName(), "종목명은 null이 아니어야 함");
        assertTrue(firstStock.amount() > 0, "거래대금은 0보다 커야 함");

        System.out.println("거래대금 상위 " + stocks.size() + "개 종목 조회 성공");
        System.out.println("1위: " + firstStock.stockName() + " (" + firstStock.stockCode() + ") - 거래대금: " + firstStock.amount() + "원");
    }

    @Test
    @DisplayName("거래대금 상위 종목 상세 데이터 확인 - 모든 필드 출력")
    void testAmountTopStocksDetailedData() {
        // Given
        int expectedCount = 10;

        // When (DB 필터링 버전 사용 - marketType 포함)
        List<StockRankingDto> stocks = stockRankingService.getAmountTopStocksFiltered(expectedCount).block();

        // Then
        assertNotNull(stocks, "조회된 종목 리스트는 null이 아니어야 함");
        assertTrue(stocks.size() > 0, "최소 1개 이상의 종목이 조회되어야 함");

        System.out.println("\n" + "=".repeat(100));
        System.out.println("거래대금 상위 " + stocks.size() + "개 종목 상세 데이터");
        System.out.println("=".repeat(100) + "\n");

        // 상위 10개 종목의 모든 데이터 출력 (가격 정보 포함)
        for (int i = 0; i < Math.min(stocks.size(), 10); i++) {
            StockRankingDto stock = stocks.get(i);

            System.out.println(String.format(
                "[%2d위] %s (%s) - %s\n" +
                "      ├─ 종목명: %s\n" +
                "      ├─ 현재가: %,d원 (%s %,d원, %s%%)\n" +
                "      ├─ 거래량: %,d주\n" +
                "      └─ 거래대금: %,d원 (%.2f억)\n",
                i + 1,
                stock.stockCode(),
                stock.stockCode(),
                stock.marketType(),
                stock.stockName(),
                stock.currentPrice(),
                stock.changeSign().getDescription(),
                Math.abs(stock.changeAmount()),
                stock.changeRate(),
                stock.volume(),
                stock.amount(),
                stock.amount() / 100_000_000.0
            ));
        }

        System.out.println("=".repeat(100));

        // 데이터 검증
        StockRankingDto firstStock = stocks.get(0);
        assertNotNull(firstStock.stockCode(), "종목코드는 null이 아니어야 함");
        assertNotNull(firstStock.stockName(), "종목명은 null이 아니어야 함");
        assertTrue(firstStock.amount() > 0, "거래대금은 0보다 커야 함");
        assertNotNull(firstStock.marketType(), "시장구분은 null이 아니어야 함");
        assertNotEquals("UNKNOWN", firstStock.marketType(), "DB에서 조회한 시장구분이 있어야 함");

        // 가격 정보 포함 확인
        System.out.println("\nStockRankingDto에 가격 정보 포함 확인:");
        StockRankingDto sample = stocks.get(0);
        System.out.println("   - 현재가: " + sample.currentPrice() + "원");
        System.out.println("   - 전일대비: " + sample.changeAmount() + "원");
        System.out.println("   - 전일대비율: " + sample.changeRate() + "%");
        System.out.println("   - 등락부호: " + sample.changeSign().getDescription() + " (" + sample.changeSign().name() + ")");
        System.out.println("   -> 메인 페이지 표시 준비 완료!");
    }

    @Test
    @DisplayName("거래대금 상위 종목 DB 필터링 비교")
    void testAmountTopStocksWithAndWithoutFiltering() {
        // Given
        int expectedCount = 20;

        // When
        List<StockRankingDto> allStocks = stockRankingService.getAmountTopStocks(expectedCount).block();
        List<StockRankingDto> filteredStocks = stockRankingService.getAmountTopStocksFiltered(expectedCount).block();

        // Then
        assertNotNull(allStocks, "전체 종목 리스트는 null이 아니어야 함");
        assertNotNull(filteredStocks, "필터링된 종목 리스트는 null이 아니어야 함");

        System.out.println("\n" + "=".repeat(80));
        System.out.println("DB 필터링 전후 비교");
        System.out.println("=".repeat(80));
        System.out.println(String.format("API 응답 전체 종목 수: %d개", allStocks.size()));
        System.out.println(String.format("DB 필터링 후 종목 수: %d개", filteredStocks.size()));
        System.out.println(String.format("필터링된 종목 수: %d개 (ETF 등)", allStocks.size() - filteredStocks.size()));
        System.out.println("=".repeat(80) + "\n");

        // 필터링된 종목의 시장구분 확인
        System.out.println("DB 필터링 후 종목 목록 (marketType 확인):");
        for (int i = 0; i < Math.min(filteredStocks.size(), 5); i++) {
            StockRankingDto stock = filteredStocks.get(i);
            System.out.println(String.format(
                    "   [%d] %s (%s) - %s",
                    i + 1,
                    stock.stockName(),
                    stock.stockCode(),
                    stock.marketType()
            ));
        }

        // marketType이 DB에서 제대로 조회되었는지 확인
        if (!filteredStocks.isEmpty()) {
            filteredStocks.forEach(stock -> {
                assertNotNull(stock.marketType(), "marketType은 null이 아니어야 함");
                assertNotEquals("UNKNOWN", stock.marketType(),
                        String.format("종목 %s의 marketType이 DB에서 조회되어야 함", stock.stockCode()));
            });
        }
    }
}
