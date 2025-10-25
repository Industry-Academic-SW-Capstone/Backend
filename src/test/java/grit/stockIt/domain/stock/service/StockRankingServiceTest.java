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
    @DisplayName("거래량 상위 종목 조회 - 30건 확인")
    void testVolumeTopStocks() {
        // Given
        int expectedCount = 30;

        // When
        List<StockRankingDto> stocks = stockRankingService.getVolumeTopStocks(expectedCount);

        // Then
        assertNotNull(stocks, "조회된 종목 리스트는 null이 아니어야 함");
        assertEquals(expectedCount, stocks.size(), "조회된 종목 수는 " + expectedCount + "개여야 함");
        
        // 첫 번째 종목 검증
        StockRankingDto firstStock = stocks.get(0);
        assertNotNull(firstStock.stockCode(), "종목코드는 null이 아니어야 함");
        assertNotNull(firstStock.stockName(), "종목명은 null이 아니어야 함");
        assertTrue(firstStock.volume() > 0, "거래량은 0보다 커야 함");
        
        System.out.println("거래량 상위 " + stocks.size() + "개 종목 조회 성공");
        System.out.println("1위: " + firstStock.stockName() + " (" + firstStock.stockCode() + ") - 거래량: " + firstStock.volume());
    }

    @Test
    @DisplayName("거래대금 상위 종목 조회 - 20건 확인")
    void testAmountTopStocks() {
        // Given
        int expectedCount = 20;

        // When
        List<StockRankingDto> stocks = stockRankingService.getAmountTopStocks(expectedCount);

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
    @DisplayName("웹소켓용 주식코드 조회 - 10건 확인 (DB 필터링)")
    void testStockCodesForWebSocket() {
        // Given
        int expectedCount = 10;

        // When
        List<String> stockCodes = stockRankingService.getVolumeTopStockCodes(expectedCount);

        // Then
        assertNotNull(stockCodes, "조회된 주식코드 리스트는 null이 아니어야 함");
        assertTrue(stockCodes.size() <= expectedCount, "조회된 주식코드 수는 " + expectedCount + "개 이하여야 함");
        
        // 모든 코드가 6자리인지 확인
        stockCodes.forEach(code -> {
            assertNotNull(code, "주식코드는 null이 아니어야 함");
            assertEquals(6, code.length(), "주식코드는 6자리여야 함: " + code);
        });
        
        System.out.println("웹소켓용 주식코드 " + stockCodes.size() + "개 조회 성공 (DB에 있는 종목만)");
        System.out.println("주식코드: " + stockCodes);
    }

    @Test
    @DisplayName("거래대금 상위 종목 코드 조회 - 10건 확인 (DB 필터링)")
    void testAmountTopStockCodes() {
        // Given
        int expectedCount = 10;

        // When
        List<String> stockCodes = stockRankingService.getAmountTopStockCodes(expectedCount);

        // Then
        assertNotNull(stockCodes, "조회된 주식코드 리스트는 null이 아니어야 함");
        assertTrue(stockCodes.size() <= expectedCount, "조회된 주식코드 수는 " + expectedCount + "개 이하여야 함");
        
        // 모든 코드가 6자리인지 확인
        stockCodes.forEach(code -> {
            assertNotNull(code, "주식코드는 null이 아니어야 함");
            assertEquals(6, code.length(), "주식코드는 6자리여야 함: " + code);
        });
        
        System.out.println("거래대금 상위 주식코드 " + stockCodes.size() + "개 조회 성공 (DB에 있는 종목만)");
        System.out.println("주식코드: " + stockCodes);
    }
}
