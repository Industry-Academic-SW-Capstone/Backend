package grit.stockIt.domain.stock.analysis.dto;

// Python 서버 /stock/report/stream 에 전달하는 요청 DTO
// 직렬화: Spring Boot 글로벌 SNAKE_CASE 전략으로 stock_code, stock_name 등으로 변환
// 역직렬화: 프론트엔드에서 전달하는 snake_case JSON → camelCase 필드
public record ReportStreamRequest(
    String stockCode,
    String stockName,
    String styleTag,
    Double growthScore,
    Double stabilityScore,
    Double compositeScore
) {}
