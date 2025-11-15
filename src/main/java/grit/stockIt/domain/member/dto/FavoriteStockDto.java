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

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd HH:mm:ss", timezone = "Asia/Seoul")
    private LocalDateTime addedAt;

    @Builder
    public FavoriteStockDto(Long favoriteId, String stockCode, String stockName, LocalDateTime addedAt) {
        this.favoriteId = favoriteId;
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.addedAt = addedAt;
    }
}
