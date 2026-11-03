package grit.stockIt.domain.member.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthProvider {
    LOCAL("일반 회원가입"),
    KAKAO("카카오 로그인");

    private final String description;
}