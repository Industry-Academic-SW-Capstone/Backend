package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.notification.event.ExecutionFilledEvent;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

/**
 * 체결 알림의 문구와 페이로드를 조립한다.
 *
 * <h2>코드로 강제되지 않는 제약</h2>
 * <ul>
 *   <li><b>반환 맵은 {@code HashMap} 이어야 한다.</b> 선언 타입이 {@code Map} 이라
 *       {@code LinkedHashMap}/{@code Map.of} 를 반환해도 컴파일은 통과하지만,
 *       {@code detailMap} 의 직렬화 결과는 {@code NotificationResponse} 에서
 *       {@code @JsonRawValue} 로 API 응답에 그대로 실린다. 맵 구현을 바꾸면
 *       키 집합은 그대로인 채 JSON 키 순서만 달라져 사용자 노출이 바뀐다.</li>
 *   <li><b>{@link #body} 의 수량은 {@code Integer} 여야 한다.</b> 원시형 {@code int} 로 바꾸면
 *       null 에서 NPE 가 나는데, 현재 동작은 {@code "null주가"} 를 출력한다.</li>
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

    /**
     * 알림 내용: "매수 10주가 70,000원에 체결되었습니다"
     *
     * <p><b>{@code Locale.ROOT} 를 반드시 명시한다.</b> 수량의 {@code %d} 는 기본 로케일의
     * {@code zeroDigit} 을 따르므로 latn 이 아닌 자릿수 체계에서 비ASCII 숫자가 실린다.
     * {@link #formatPrice} 만 고치면 같은 문자열 안에서 금액은 latn 인데 수량은 아랍-인도 숫자가 되는
     * 혼종 출력이 된다.
     */
    public static String body(String orderMethod, Integer quantity, BigDecimal price) {
        return String.format(Locale.ROOT, "%s %d주가 %s원에 체결되었습니다",
                orderMethodKorean(orderMethod), quantity, formatPrice(price));
    }

    /**
     * 금액 천 단위 구분 포맷.
     *
     * <p>{@code %,d} 는 기본 로케일의 자릿수 체계와 구분 기호를 따른다.
     * {@code Locale.ROOT} 고정이 없으면 ar-SA 에서 아랍-인도 숫자, de-DE 에서 점 구분자가 된다.
     */
    public static String formatPrice(BigDecimal price) {
        return String.format(Locale.ROOT, "%,d", price.intValue());
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
