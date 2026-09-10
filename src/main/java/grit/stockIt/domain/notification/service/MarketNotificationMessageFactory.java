package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.notification.enums.NotificationType;

import java.util.HashMap;
import java.util.Map;

/**
 * 장 시작·마감 알림의 문구와 페이로드를 조립하는 순수 계산 클래스.
 *
 * <p><b>변경 축: 장 시작·마감 알림의 문구와 페이로드 구성이 바뀔 때만 이 클래스를 고친다.</b>
 *
 * <p>문구 리터럴 4개가 이 클래스의 상수로 들어와 있다. 서비스에 남겨두면 축 주장이 거짓이 된다 —
 * 문구가 바뀔 때 서비스도 고쳐야 하기 때문이다. 문자열이 동일하므로 이 이동은 동작 불변이다.
 *
 * <p>협력자 0개. 설계 규약은 {@link ExecutionNotificationMessageFactory} 와 동일하다.
 *
 * <h2>{@code nowMillis} 획득 규약 (중요)</h2>
 * 호출부는 {@code System.currentTimeMillis()} 를 <b>회원 루프 안에서 회원당 1회</b> 캡처해
 * 같은 값을 {@link #detailMap} 과 {@link #fcmData} 에 넘긴다.
 * 루프 <b>밖</b> 캡처는 한 브로드캐스트의 모든 회원이 {@code sentAt} 을 공유하게 만드는
 * <b>별개의 동작 변경</b>이며 결함 ③-a 의 범위 밖이다.
 *
 * <p>{@code long} 파라미터 2개가 동일 값을 <b>강제하지는 않는다</b>.
 * "구조적 보장"이 아니라 이 규약과 결함 동결 파일의 DF-4 오라클이 보장한다.
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
