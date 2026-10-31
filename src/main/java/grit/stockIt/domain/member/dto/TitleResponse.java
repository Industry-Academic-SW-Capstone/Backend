package grit.stockIt.domain.member.dto;

import grit.stockIt.contract.TitleView;

// 프로필에 보여 줄 보유 칭호. 칭호는 부가 데이터라 부가가 알려 준 내용으로 만든다.
public record TitleResponse(String name, String description) {

    public static TitleResponse from(TitleView title) {
        return new TitleResponse(title.name(), title.description());
    }
}
