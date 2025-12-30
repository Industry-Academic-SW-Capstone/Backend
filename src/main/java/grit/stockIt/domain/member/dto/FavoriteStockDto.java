package grit.stockIt.domain.member.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class FavoriteStockDto {

    private Long favoriteId;
    private String stockCode;
    private String stockName;
    private String marketType;
    private Integer currentPrice;
    private Double changeRate;
    private String changeSign;
    private Integer changeAmount;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime addedAt;

    @Builder
    public FavoriteStockDto(Long favoriteId, String stockCode, String stockName, LocalDateTime addedAt,
                            String marketType, Integer currentPrice, Double changeRate, String changeSign, Integer changeAmount) {
        this.favoriteId = favoriteId;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.addedAt = addedAt;
        this.marketType = marketType;
        this.currentPrice = currentPrice;
        this.changeRate = changeRate;
        this.changeSign = changeSign;
        this.changeAmount = changeAmount;
    }
}
