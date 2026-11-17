package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.notification.event.ExecutionFilledEvent;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;

// 체결 알림의 문구와 페이로드 조립
public final class ExecutionNotificationMessageFactory {

    private ExecutionNotificationMessageFactory() {
    }

    private static String orderMethodKorean(String orderMethod) {
        return "BUY".equals(orderMethod) ? "매수" : "매도";
    }

    public static String title(String stockName, String orderMethod) {
        return String.format("%s %s 체결", stockName, orderMethodKorean(orderMethod));
    }

    // 수량의 %d 도 로케일 의존이다. formatPrice 만 고치면 금액은 latn 인데
    // 수량만 비ASCII 숫자가 되는 혼종 출력이 된다.
    public static String body(String orderMethod, Integer quantity, BigDecimal price) {
        return String.format(Locale.ROOT, "%s %d주가 %s원에 체결되었습니다",
                orderMethodKorean(orderMethod), quantity, formatPrice(price));
    }


    public static String formatPrice(BigDecimal price) {
        return String.format(Locale.ROOT, "%,d", price.intValue());
    }

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

    // title/body 는 PWA Service Worker 가 읽는다
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
