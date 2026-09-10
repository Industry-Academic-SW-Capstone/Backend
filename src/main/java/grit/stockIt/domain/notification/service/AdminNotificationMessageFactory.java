package grit.stockIt.domain.notification.service;

import java.util.HashMap;
import java.util.Map;

/**
 * 관리자 공지 알림의 페이로드를 조립하는 순수 계산 클래스.
 *
 * <p><b>변경 축: 관리자 공지 알림의 페이로드 맵 구성이 바뀔 때만 이 클래스를 고친다.</b>
 * 문구는 런타임 인자로 들어오므로 축에서 제외한다 — 다른 세 팩토리와 달리
 * 이 서비스는 제목과 본문을 호출자에게서 받는다.
 *
 * <p>협력자 0개. 설계 규약은 {@link ExecutionNotificationMessageFactory} 와 동일하다.
 *
 * <h2>두 개의 독립 {@code nowMillis} (의도된 현행 동작)</h2>
 * 호출부는 {@link #detailMap} 과 {@link #fcmData} 에 <b>서로 다른</b>
 * {@code System.currentTimeMillis()} 값을 넘긴다. 현행 동작이 그렇기 때문이다.
 * 두 값을 하나로 합치면 동작이 바뀌는데 <b>어떤 오라클도 그 변경을 잡지 못한다</b> —
 * 결함 동결 파일의 DF-4 는 Market 만 다루기 때문이다.
 * 정착된 결함 범위(③-a = Market 만)를 넘지 않기 위해 두 값을 그대로 보존한다.
 * 통일은 미결 항목으로 PR 본문에 올린다.
 *
 * <p>키 이름도 갈린다: {@code detailMap} 은 {@code sentAt}, {@code fcmData} 는 {@code timestamp} 다.
 * 이 역시 현행 동작이며 통일은 API 계약 변경이라 미결 항목이다.
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
