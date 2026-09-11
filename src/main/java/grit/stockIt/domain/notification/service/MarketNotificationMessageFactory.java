package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.notification.enums.NotificationType;

import java.util.HashMap;
import java.util.Map;

/**
 * 장 시작·마감 알림의 문구와 페이로드를 조립한다.
 *
 * <h2>코드로 강제되지 않는 제약</h2>
 * <ul>
 *   <li><b>반환 맵은 {@code HashMap} 이어야 한다.</b> 선언 타입이 {@code Map} 이라 다른 구현을
 *       반환해도 컴파일은 통과하지만, {@code detailMap} 의 직렬화 결과가
 *       {@code @JsonRawValue} 로 API 응답에 실리므로 키 순서 변경이 곧 사용자 노출 변경이다.</li>
 *   <li><b>호출부는 {@code nowMillis} 를 회원 루프 안에서 회원당 1회 읽어야 한다.</b>
 *       루프 밖에서 읽으면 한 브로드캐스트의 모든 회원이 같은 {@code sentAt} 을 공유하게 되어
 *       회원별 알림 시각이라는 의미가 바뀐다. 파라미터 2개가 같은 값을 강제하지는 않으므로
 *       이 규약과 결함 동결 테스트가 함께 보장한다.</li>
 * </ul>
 */
public final class MarketNotificationMessageFactory {

    /** 장 시작 알림 제목. */
    public static final String MARKET_OPEN_TITLE = "장 시작 알림";

    /** 장 시작 알림 본문. */
    public static final String MARKET_OPEN_MESSAGE = "주식 시장이 시작되었습니다. 오늘도 좋은 하루 되세요!";

    /** 장 마감 30분 전 알림 제목. */
    public static final String MARKET_CLOSE_TITLE = "장 마감 30분 전";

    /** 장 마감 30분 전 알림 본문. */
    public static final String MARKET_CLOSE_MESSAGE = "장이 30분 후에 마감됩니다.";

    /** 장 시작 알림 아이콘. */
    public static final String MARKET_OPEN_ICON = "market_open";

    /** 장 마감 알림 아이콘. */
    public static final String MARKET_CLOSE_ICON = "market_close";

    private MarketNotificationMessageFactory() {
    }

    /** DB {@code detailData} 로 직렬화될 맵. 2키. */
    public static Map<String, Object> detailMap(NotificationType notificationType, long nowMillis) {
        Map<String, Object> detailMap = new HashMap<>();
        detailMap.put("type", notificationType.name());
        detailMap.put("sentAt", nowMillis);
        return detailMap;
    }

    /** FCM data-only 페이로드. 4키. */
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
