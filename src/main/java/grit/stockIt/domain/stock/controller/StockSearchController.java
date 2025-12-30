package grit.stockIt.domain.stock.controller;

import grit.stockIt.domain.stock.dto.StockSearchDto;
import grit.stockIt.domain.stock.service.StockSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/stocks")
@RequiredArgsConstructor
@Tag(name = "Stock", description = "종목 관련 API")
public class StockSearchController {

    private final StockSearchService stockSearchService;

    @Operation(summary = "종목 검색", description = "종목명을 기준으로 자카드 유사도 내림차순으로 결과 반환(유사도 0 초과)")
    @GetMapping("/search")
    public ResponseEntity<List<StockSearchDto>> searchStocks(@RequestParam(name = "q") String q) {
        List<StockSearchDto> results = stockSearchService.searchByName(q);
        return ResponseEntity.ok(results);
    }
}
