package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.notification.enums.NotificationType;

import java.util.HashMap;
import java.util.Map;

// 장 시작·마감 알림의 문구와 페이로드 조립
public final class MarketNotificationMessageFactory {

    // 호출부는 nowMillis 를 회원 루프 안에서 회원당 1회 읽는다.
    // 루프 밖에서 읽으면 한 브로드캐스트의 모든 회원이 같은 sentAt 을 공유하게 되어
    // 회원별 알림 시각이라는 의미가 바뀐다.

    public static final String MARKET_OPEN_TITLE = "장 시작 알림";

    public static final String MARKET_OPEN_MESSAGE = "주식 시장이 시작되었습니다. 오늘도 좋은 하루 되세요!";

    public static final String MARKET_CLOSE_TITLE = "장 마감 30분 전";

    public static final String MARKET_CLOSE_MESSAGE = "장이 30분 후에 마감됩니다.";

    public static final String MARKET_OPEN_ICON = "market_open";

    public static final String MARKET_CLOSE_ICON = "market_close";

    private MarketNotificationMessageFactory() {
    }

    public static Map<String, Object> detailMap(NotificationType notificationType, long nowMillis) {
        Map<String, Object> detailMap = new HashMap<>();
        detailMap.put("type", notificationType.name());
        detailMap.put("sentAt", nowMillis);
        return detailMap;
    }

    public static Map<String, String> fcmData(String title,
                                              String message,
                                              NotificationType notificationType,
                                              long nowMillis) {
        Map<String, String> data = new HashMap<>();
        data.put("title", title);
        data.put("body", message);
        data.put("type", notificationType.name());
        data.put("sentAt", String.valueOf(nowMillis));
        return data;
    }
}
