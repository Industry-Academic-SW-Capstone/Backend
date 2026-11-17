package grit.stockIt.global.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record KisTokenStatusResponse(
        Boolean accessTokenExists,
        Boolean approvalKeyExists,
        String message
) {
    public static KisTokenStatusResponse of(Boolean accessTokenExists, Boolean approvalKeyExists) {
        return new KisTokenStatusResponse(
                accessTokenExists,
                approvalKeyExists,
                "토큰 상태 조회 완료"
        );
    }
}

