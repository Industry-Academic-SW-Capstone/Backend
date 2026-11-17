package grit.stockIt.global.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KisTokenRefreshResponse(
        String message,
        String tokenType,
        String token,
        Boolean success
) {
    public static KisTokenRefreshResponse success(String tokenType, String token) {
        return new KisTokenRefreshResponse(
                tokenType + " 갱신 성공",
                tokenType,
                token,
                true
        );
    }

    public static KisTokenRefreshResponse error(String message) {
        return new KisTokenRefreshResponse(
                message,
                null,
                null,
                false
        );
    }
}

