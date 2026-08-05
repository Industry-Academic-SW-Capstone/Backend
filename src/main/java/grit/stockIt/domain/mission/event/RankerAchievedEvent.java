package grit.stockIt.domain.mission.event;

import java.util.List;

// 랭커 달성 이벤트 — 랭킹 갱신 트랜잭션 내부에서 동기 발행된다 (Main 랭킹 Top 10 회원 ID 명단 운반)
public record RankerAchievedEvent(List<Long> top10MemberIds) {

    public RankerAchievedEvent {
        // 이벤트는 값이다 — 발행자 리스트 참조를 방어 복사해 완전 불변으로 고정
        top10MemberIds = List.copyOf(top10MemberIds);
    }
}
