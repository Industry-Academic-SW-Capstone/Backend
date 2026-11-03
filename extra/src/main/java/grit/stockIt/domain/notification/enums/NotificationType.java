package grit.stockIt.domain.notification.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NotificationType {
    EXECUTION("주문 체결"),           // 주문 체결 완료
    MISSION_COMPLETED("미션 완료"),   // 미션 완료
    RANKING("랭킹 변동"),             // 전체 랭킹 변동 (향후 구현)
    ACHIEVEMENT("업적 달성"),         // 업적 달성 (향후 구현)
    CONTEST("대회 순위 변동"),         // 대회 순위 변동 (향후 구현)
    SYSTEM("시스템 공지"),            // 시스템 점검/공지 (향후 구현)
    MARKET_OPEN("장 시작"),           // 장 시작 알림
    MARKET_CLOSE_REMINDER("장 마감 30분 전");  // 장 마감 30분 전 알림

    private final String description;
}

