package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.notification.event.MissionCompletedEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// 미션 완료 알림의 문구와 페이로드 조립
public final class MissionNotificationMessageFactory {

    private MissionNotificationMessageFactory() {
    }

    public static String title(String missionName) {
        return String.format("%s 완료!", missionName);
    }

    public static String body(long moneyAmount, String titleName) {
        StringBuilder builder = new StringBuilder();
        if (moneyAmount > 0) {
            builder.append(String.format(Locale.ROOT, "보상으로 %,d원을 받았습니다.", moneyAmount));
        }
        if (titleName != null) {
            if (builder.length() > 0) {
                builder.append(" ");
            }
            builder.append(String.format("칭호 '%s'를 획득했습니다.", titleName));
        }
        if (builder.length() == 0) {
            builder.append("미션을 완료했습니다!");
        }
        return builder.toString();
    }

    public static String iconType(MissionTrack track) {
        return switch (track) {
            case DAILY -> "mission_daily";
            case SHORT_TERM -> "mission_short_term";
            case SWING -> "mission_swing";
            case LONG_TERM -> "mission_long_term";
            case ACHIEVEMENT -> "mission_achievement";
        };
    }

    public static Map<String, Object> detailMap(MissionCompletedEvent event) {
        Map<String, Object> detailMap = new HashMap<>();
        detailMap.put("missionId", event.missionId());
        detailMap.put("missionName", event.missionName());
        detailMap.put("track", event.track().name());
        detailMap.put("rewardId", event.rewardId());
        detailMap.put("moneyAmount", event.moneyAmount());
        detailMap.put("titleName", event.titleName());
        return detailMap;
    }

    // null 정책이 detailMap 과 반대다. detailMap 은 null 을 그대로 유지한다
    public static Map<String, String> fcmData(MissionCompletedEvent event,
                                              String title,
                                              String body,
                                              long nowMillis) {
        Map<String, String> data = new HashMap<>();
        data.put("title", title);
        data.put("body", body);
        data.put("type", "MISSION_COMPLETED");
        data.put("missionId", String.valueOf(event.missionId()));
        data.put("missionName", event.missionName());
        data.put("track", event.track().name());
        data.put("rewardId", event.rewardId() != null ? String.valueOf(event.rewardId()) : "");
        data.put("moneyAmount", String.valueOf(event.moneyAmount()));
        data.put("titleName", event.titleName() != null ? event.titleName() : "");
        data.put("completedAt", String.valueOf(nowMillis));
        return data;
    }
}
