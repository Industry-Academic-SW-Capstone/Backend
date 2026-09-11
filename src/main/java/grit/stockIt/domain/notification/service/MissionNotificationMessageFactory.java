package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.notification.event.MissionCompletedEvent;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 미션 완료 알림의 문구와 페이로드를 조립한다.
 *
 * <h2>코드로 강제되지 않는 제약</h2>
 * <ul>
 *   <li><b>반환 맵은 {@code HashMap} 이어야 한다.</b> 선언 타입이 {@code Map} 이라 다른 구현을
 *       반환해도 컴파일은 통과하지만, {@code detailMap} 의 직렬화 결과가
 *       {@code @JsonRawValue} 로 API 응답에 실리므로 키 순서 변경이 곧 사용자 노출 변경이다.</li>
 *   <li><b>{@link #iconType} 이 {@code MissionTrack} 을 다루지만 트랙 추가는 이 클래스의
 *       변경 축이 아니다.</b> {@code MissionTrack} 참조는 mission 도메인 전역 29곳이고
 *       새 트랙 추가 시 컴파일러가 막는 곳은 이 exhaustive switch 1곳뿐이다.
 *       나머지 28곳(맵 초기화·조건 분기·조회 조건)은 조용히 누락된다.</li>
 *   <li><b>{@link #detailMap} 은 null 을 유지하고 {@link #fcmData} 는 빈 문자열로 치환한다.</b>
 *       두 맵의 null 정책이 다르다.</li>
 * </ul>
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
     * <p><b>금액 포맷에 {@code Locale.ROOT} 를 명시한다.</b> {@code %,d} 는 기본 로케일의
     * 자릿수 체계와 구분 기호를 따르므로 고정하지 않으면 비latn 로케일에서 숫자 표기가 바뀐다.
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
