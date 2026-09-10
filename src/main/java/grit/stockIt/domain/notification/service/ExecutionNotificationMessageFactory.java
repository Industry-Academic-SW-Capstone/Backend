package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.notification.event.ExecutionFilledEvent;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 체결 알림의 문구와 페이로드를 조립하는 순수 계산 클래스.
 *
 * <p><b>변경 축: 체결 알림의 문구와 페이로드 구성이 바뀔 때만 이 클래스를 고친다.</b>
 *
 * <p>협력자 0개다. {@code FcmService}·{@code MemberRepository}·{@code NotificationRepository}·
 * {@code ObjectMapper} 는 전부 {@link ExecutionNotificationService} 에 남는다.
 * 이 클래스는 값만 받아 값만 돌려준다.
 *
 * <h2>설계 규약</h2>
 * <ul>
 *   <li>{@code System.currentTimeMillis()} 를 호출하지 않는다. 시각은 {@code nowMillis} 로 받는다.
 *       {@code now()} 는 호출부에 남기고 순수 함수는 값을 파라미터로 받는다는 원칙이다.</li>
 *   <li>{@code ObjectMapper} 에 의존하지 않는다. 맵까지만 만들고 직렬화는 서비스가 한다.</li>
 *   <li><b>반환 맵은 반드시 {@code new HashMap<>()} 이다.</b> {@code LinkedHashMap}/{@code Map.of}/
 *       {@code TreeMap} 으로 바꾸면 키 집합은 그대로인데 직렬화 JSON 의 순서가 바뀐다.
 *       {@code detailData} 는 {@code NotificationResponse} 에서 {@code @JsonRawValue} 로
 *       API 응답에 그대로 실리므로 순서 변경은 곧 사용자 노출 변경이다.</li>
 *   <li>{@link #body} 의 수량은 {@code Integer} 다. 원시형 {@code int} 로 바꾸면 null 에서 NPE 가 나는데
 *       현재 동작은 {@code "null주가"} 출력이므로 동작 불변이 깨진다.</li>
 * </ul>
 */
public final class ExecutionNotificationMessageFactory {

    private ExecutionNotificationMessageFactory() {
    }

    /** 매수/매도 한글 표기. */
    private static String orderMethodKorean(String orderMethod) {
        return "BUY".equals(orderMethod) ? "매수" : "매도";
    }

    /** 알림 제목: "삼성전자 매수 체결" */
    public static String title(String stockName, String orderMethod) {
        return String.format("%s %s 체결", stockName, orderMethodKorean(orderMethod));
    }

    /** 알림 내용: "매수 10주가 70,000원에 체결되었습니다" */
    public static String body(String orderMethod, Integer quantity, BigDecimal price) {
        return String.format("%s %d주가 %s원에 체결되었습니다",
                orderMethodKorean(orderMethod), quantity, formatPrice(price));
    }

    /** 금액 천 단위 구분 포맷. */
    public static String formatPrice(BigDecimal price) {
        return String.format("%,d", price.intValue());
    }

    /** DB {@code detailData} 로 직렬화될 맵. 10키. 타임스탬프 없음. */
    public static Map<String, Object> detailMap(ExecutionFilledEvent event) {
        Map<String, Object> detailMap = new HashMap<>();
        detailMap.put("executionId", event.executionId());
        detailMap.put("orderId", event.orderId());
        detailMap.put("accountId", event.accountId());
        detailMap.put("contestId", event.contestId());
        detailMap.put("contestName", event.contestName());
        detailMap.put("stockCode", event.stockCode());
        detailMap.put("stockName", event.stockName());
        detailMap.put("price", event.price());
        detailMap.put("quantity", event.quantity());
        detailMap.put("orderMethod", event.orderMethod());
        return detailMap;
    }

    /** FCM data-only 페이로드. 14키. title/body 는 PWA Service Worker 가 쓴다. */
    public static Map<String, String> fcmData(ExecutionFilledEvent event,
                                              String title,
                                              String body,
                                              long nowMillis) {
        Map<String, String> data = new HashMap<>();
        data.put("title", title);
        data.put("body", body);
        data.put("type", "EXECUTION");
        data.put("executionId", String.valueOf(event.executionId()));
        data.put("orderId", String.valueOf(event.orderId()));
        data.put("accountId", String.valueOf(event.accountId()));
        data.put("contestId", String.valueOf(event.contestId()));
        data.put("contestName", event.contestName());
        data.put("stockCode", event.stockCode());
        data.put("stockName", event.stockName());
        data.put("price", event.price().toString());
        data.put("quantity", String.valueOf(event.quantity()));
        data.put("orderMethod", event.orderMethod());
        data.put("executedAt", String.valueOf(nowMillis));
        return data;
    }
}
