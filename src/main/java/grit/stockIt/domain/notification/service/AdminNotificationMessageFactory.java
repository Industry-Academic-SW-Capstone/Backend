package grit.stockIt.domain.notification.service;

import java.util.HashMap;
import java.util.Map;

// 관리자 공지 알림의 페이로드 조립
public final class AdminNotificationMessageFactory {

    private AdminNotificationMessageFactory() {
    }

    public static Map<String, Object> detailMap(String title, String body, long nowMillis) {
        Map<String, Object> detailMap = new HashMap<>();
        detailMap.put("title", title);
        detailMap.put("body", body);
        detailMap.put("type", "SYSTEM");
        detailMap.put("sentAt", nowMillis);
        return detailMap;
    }

    public static Map<String, String> fcmData(String title, String body, long nowMillis) {
        Map<String, String> data = new HashMap<>();
        data.put("title", title);
        data.put("body", body);
        data.put("type", "SYSTEM");
        data.put("timestamp", String.valueOf(nowMillis));
        return data;
    }
}
