package grit.stockIt.domain.member.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CreateFavoriteRequest {

    @NotBlank
    private String stockCode;

    @JsonCreator
    public CreateFavoriteRequest(@JsonProperty("stock_code") String stockCode) {
        if (stockCode != null) {
            stockCode = stockCode.trim();
        }
        this.stockCode = stockCode;
    }
}
