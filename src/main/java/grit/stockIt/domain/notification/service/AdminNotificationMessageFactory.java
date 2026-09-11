package grit.stockIt.domain.notification.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 관리자 공지 알림의 페이로드를 조립한다.
 *
 * <h2>코드로 강제되지 않는 제약</h2>
 * <ul>
 *   <li><b>반환 맵은 {@code HashMap} 이어야 한다.</b> 선언 타입이 {@code Map} 이라 다른 구현을
 *       반환해도 컴파일은 통과하지만, {@code detailMap} 의 직렬화 결과가
 *       {@code @JsonRawValue} 로 API 응답에 실리므로 키 순서 변경이 곧 사용자 노출 변경이다.</li>
 *   <li><b>타임스탬프 키 이름이 갈린다.</b> {@link #detailMap} 은 {@code sentAt},
 *       {@link #fcmData} 는 {@code timestamp} 를 쓴다. 현행 API 계약이다.</li>
 *   <li><b>두 맵은 서로 다른 시각을 받을 수 있다.</b> 호출부가 각각 독립적으로 시각을 읽는다.
 *       하나로 합치면 동작이 바뀌므로 결함 동결 테스트가 현행 동작을 고정한다.</li>
 * </ul>
 */
public final class AdminNotificationMessageFactory {

    private AdminNotificationMessageFactory() {
    }

    /** DB {@code detailData} 로 직렬화될 맵. 4키. 타임스탬프 키는 {@code sentAt}. */
    public static Map<String, Object> detailMap(String title, String body, long nowMillis) {
        Map<String, Object> detailMap = new HashMap<>();
        detailMap.put("title", title);
        detailMap.put("body", body);
        detailMap.put("type", "SYSTEM");
        detailMap.put("sentAt", nowMillis);
        return detailMap;
    }

    /** FCM data-only 페이로드. 4키. 타임스탬프 키는 {@code timestamp}. */
    public static Map<String, String> fcmData(String title, String body, long nowMillis) {
        Map<String, String> data = new HashMap<>();
        data.put("title", title);
        data.put("body", body);
        data.put("type", "SYSTEM");
        data.put("timestamp", String.valueOf(nowMillis));
        return data;
    }
}
