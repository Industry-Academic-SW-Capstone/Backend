package grit.stockIt.domain.member.event;

import grit.stockIt.domain.member.entity.Member;

// 회원 가입 완료 이벤트 — 가입 트랜잭션 내부에서 동기 발행된다 (저장된 Member를 그대로 전달, 재조회 금지)
public record MemberRegisteredEvent(Member member) {
}
