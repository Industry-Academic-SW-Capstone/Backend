package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.notification.event.MissionCompletedEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 미션 완료 알림의 문구와 페이로드를 조립하는 순수 계산 클래스.
 *
 * <p><b>변경 축: 미션 완료 알림의 문구와 페이로드 구성이 바뀔 때만 이 클래스를 고친다.</b>
 *
 * <p><b>{@code MissionTrack} 추가는 이 클래스의 변경 축이 아니다.</b>
 * {@link #iconType} 이 {@code MissionTrack} 을 switch 하므로 "미션 트랙 추가 축"이라 부르고 싶어지지만
 * 실측이 이를 부정한다. {@code MissionTrack} 참조는 mission 도메인 전역 <b>29곳</b>이고,
 * 새 트랙을 추가할 때 컴파일러가 막아주는 곳은 이 exhaustive switch <b>1곳뿐</b>이다.
 * 나머지 28곳(맵 초기화·조건 분기·조회 조건)은 컴파일 에러 없이 조용히 누락된다.
 * 변경 축 판정 기준은 "X 를 바꿀 때 이 클래스만 고치면 되는가"이며 답이 명백히 아니오다.
 * 트랙 추가는 mission 도메인 전역의 축이다.
 *
 * <p>협력자 0개. 설계 규약은 {@link ExecutionNotificationMessageFactory} 와 동일하다:
 * {@code System.currentTimeMillis()} 호출 금지, {@code ObjectMapper} 의존 금지,
 * 반환 맵은 반드시 {@code new HashMap<>()}.
 */
public final class MissionNotificationMessageFactory {

    private MissionNotificationMessageFactory() {
    }

    /** 알림 제목: "첫 거래 완료!" */
    public static String title(String missionName) {
        return String.format("%s 완료!", missionName);
    }

    /**
     * 알림 내용. 보상 금액과 칭호 유무에 따라 세 갈래다.
     *
     * <ul>
     *   <li>금액만: "보상으로 50,000원을 받았습니다."</li>
     *   <li>칭호만: "칭호 '주식왕'를 획득했습니다."</li>
     *   <li>둘 다: 공백으로 이어붙인다</li>
     *   <li>둘 다 없음: "미션을 완료했습니다!"</li>
     * </ul>
     */
    public static String body(long moneyAmount, String titleName) {
        StringBuilder builder = new StringBuilder();
        if (moneyAmount > 0) {
            builder.append(String.format("보상으로 %,d원을 받았습니다.", moneyAmount));
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

    /** 미션 트랙에 따른 아이콘 타입. */
    public static String iconType(MissionTrack track) {
        return switch (track) {
            case DAILY -> "mission_daily";
            case SHORT_TERM -> "mission_short_term";
            case SWING -> "mission_swing";
            case LONG_TERM -> "mission_long_term";
            case ACHIEVEMENT -> "mission_achievement";
        };
    }

    /** DB {@code detailData} 로 직렬화될 맵. 6키. 타임스탬프 없음. */
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

    /**
     * FCM data-only 페이로드. 10키.
     *
     * <p>null 정책이 {@link #detailMap} 과 다르다: 여기서는 {@code rewardId}·{@code titleName} 의
     * null 을 빈 문자열로 치환하지만 detailMap 은 null 을 그대로 유지한다.
     */
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
