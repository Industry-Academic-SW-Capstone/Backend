package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.notification.event.MissionCompletedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MissionNotificationMessageFactory} 단위 테스트. 협력자 0개.
 */
class MissionNotificationMessageFactoryTest {

    private static MissionCompletedEvent event(long money, String titleName, MissionTrack track) {
        return new MissionCompletedEvent(100L, 200L, "첫 거래", track, 300L, money, titleName);
    }

    @Nested
    @DisplayName("title: 미션명에 완료 표시를 붙인다")
    class Title {

        @Test
        void appendsCompletionSuffix() {
            assertThat(MissionNotificationMessageFactory.title("첫 거래")).isEqualTo("첫 거래 완료!");
        }

        @Test
        void emptyName() {
            assertThat(MissionNotificationMessageFactory.title("")).isEqualTo(" 완료!");
        }

        @Test
        void nullName() {
            assertThat(MissionNotificationMessageFactory.title(null)).isEqualTo("null 완료!");
        }
    }

    @Nested
    @DisplayName("body: 금액과 칭호 유무에 따라 네 갈래다")
    class Body {

        @Test
        void moneyOnly() {
            assertThat(MissionNotificationMessageFactory.body(50000L, null))
                    .isEqualTo("보상으로 50,000원을 받았습니다.");
        }

        @Test
        void titleOnly() {
            assertThat(MissionNotificationMessageFactory.body(0L, "주식왕"))
                    .isEqualTo("칭호 '주식왕'를 획득했습니다.");
        }

        /**
         * 두 조각이 정확히 공백 하나로 이어진다. 선행/후행 공백이 없어야 한다.
         */
        @Test
        void moneyAndTitleJoinedBySingleSpace() {
            assertThat(MissionNotificationMessageFactory.body(50000L, "주식왕"))
                    .isEqualTo("보상으로 50,000원을 받았습니다. 칭호 '주식왕'를 획득했습니다.");
        }

        @Test
        void neitherFallsBackToDefault() {
            assertThat(MissionNotificationMessageFactory.body(0L, null))
                    .isEqualTo("미션을 완료했습니다!");
        }

        /**
         * 경계: moneyAmount 가 0 이면 금액 문구가 나오지 않는다.
         * 1 이면 나온다. 이 경계가 off-by-one 의 판별 지점이다.
         */
        @ParameterizedTest
        @CsvSource({
                "0,  '미션을 완료했습니다!'",
                "1,  '보상으로 1원을 받았습니다.'",
                "999, '보상으로 999원을 받았습니다.'",
                "1000, '보상으로 1,000원을 받았습니다.'"
        })
        void moneyBoundary(long money, String expected) {
            assertThat(MissionNotificationMessageFactory.body(money, null)).isEqualTo(expected);
        }

        /**
         * 경계: 음수 금액은 0 과 같은 취급이다(> 0 조건이므로).
         */
        @Test
        void negativeMoneyTreatedAsNone() {
            assertThat(MissionNotificationMessageFactory.body(-100L, null))
                    .isEqualTo("미션을 완료했습니다!");
        }

        /**
         * 빈 문자열 칭호는 null 이 아니므로 칭호 문구가 나온다.
         */
        @Test
        void emptyTitleNameStillProducesTitleClause() {
            assertThat(MissionNotificationMessageFactory.body(0L, ""))
                    .isEqualTo("칭호 ''를 획득했습니다.");
        }

        /**
         * 금액 0 + 빈 칭호 조합. 선행 공백이 붙지 않아야 한다.
         * builder.length() > 0 조건이 >= 0 으로 바뀌면 여기가 red 가 된다.
         */
        @Test
        void zeroMoneyWithEmptyTitleHasNoLeadingSpace() {
            assertThat(MissionNotificationMessageFactory.body(0L, ""))
                    .doesNotStartWith(" ");
        }

        @Test
        void bodyIsLocaleIndependent() {
            Locale orig = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-SA"));
                assertThat(MissionNotificationMessageFactory.body(50000L, null))
                        .isEqualTo("보상으로 50,000원을 받았습니다.");
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, orig);
            }
        }
    }

    @Nested
    @DisplayName("iconType: 트랙별 아이콘 매핑")
    class IconType {

        @ParameterizedTest
        @CsvSource({
                "DAILY,       mission_daily",
                "SHORT_TERM,  mission_short_term",
                "SWING,       mission_swing",
                "LONG_TERM,   mission_long_term",
                "ACHIEVEMENT, mission_achievement"
        })
        void mapsEachTrack(MissionTrack track, String expected) {
            assertThat(MissionNotificationMessageFactory.iconType(track)).isEqualTo(expected);
        }

        /**
         * 모든 트랙이 서로 다른 아이콘을 갖는다. case 교체 뮤테이션이 여기서 잡힌다.
         */
        @Test
        void allTracksHaveDistinctIcons() {
            assertThat(java.util.Arrays.stream(MissionTrack.values())
                    .map(MissionNotificationMessageFactory::iconType)
                    .distinct()
                    .count())
                    .isEqualTo(MissionTrack.values().length);
        }

        @ParameterizedTest
        @EnumSource(MissionTrack.class)
        void everyTrackHasNonNullIcon(MissionTrack track) {
            assertThat(MissionNotificationMessageFactory.iconType(track))
                    .isNotNull()
                    .startsWith("mission_");
        }
    }

    @Nested
    @DisplayName("detailMap: 6키, null 유지")
    class DetailMap {

        @Test
        void hasExactly6Keys() {
            Map<String, Object> m = MissionNotificationMessageFactory.detailMap(event(0L, null, MissionTrack.DAILY));

            assertThat(m).hasSize(6);
            assertThat(m.keySet()).containsExactlyInAnyOrder(
                    "missionId", "missionName", "track", "rewardId", "moneyAmount", "titleName");
        }

        @Test
        void isHashMap() {
            assertThat(MissionNotificationMessageFactory.detailMap(event(0L, null, MissionTrack.DAILY)))
                    .isExactlyInstanceOf(HashMap.class);
        }

        /**
         * detailMap 은 null 을 그대로 유지한다. fcmData 와 정책이 다르다.
         */
        @Test
        void keepsNullTitleName() {
            Map<String, Object> m = MissionNotificationMessageFactory.detailMap(event(0L, null, MissionTrack.DAILY));

            assertThat(m).containsKey("titleName");
            assertThat(m.get("titleName")).isNull();
        }

        @Test
        void trackIsStoredAsName() {
            assertThat(MissionNotificationMessageFactory.detailMap(event(0L, null, MissionTrack.SWING)))
                    .containsEntry("track", "SWING");
        }
    }

    @Nested
    @DisplayName("fcmData: 10키, null 은 빈 문자열")
    class FcmData {

        private Map<String, String> data(long money, String titleName) {
            return MissionNotificationMessageFactory.fcmData(
                    event(money, titleName, MissionTrack.DAILY), "제목", "본문", 1700000000000L);
        }

        @Test
        void hasExactly10Keys() {
            assertThat(data(0L, null)).hasSize(10);
            assertThat(data(0L, null).keySet()).containsExactlyInAnyOrder(
                    "title", "body", "type",
                    "missionId", "missionName", "track",
                    "rewardId", "moneyAmount", "titleName", "completedAt");
        }

        @Test
        void isHashMap() {
            assertThat(data(0L, null)).isExactlyInstanceOf(HashMap.class);
        }

        /**
         * fcmData 는 null 을 빈 문자열로 치환한다. detailMap 과 정책이 다르다.
         */
        @Test
        void nullTitleNameBecomesEmptyString() {
            assertThat(data(0L, null)).containsEntry("titleName", "");
        }

        @Test
        void presentTitleNameIsKept() {
            assertThat(data(0L, "주식왕")).containsEntry("titleName", "주식왕");
        }

        @Test
        void nullRewardIdBecomesEmptyString() {
            MissionCompletedEvent e = new MissionCompletedEvent(
                    100L, 200L, "첫 거래", MissionTrack.DAILY, null, 0L, null);

            assertThat(MissionNotificationMessageFactory.fcmData(e, "t", "b", 1L))
                    .containsEntry("rewardId", "");
        }

        @Test
        void usesProvidedNowMillis() {
            assertThat(data(0L, null)).containsEntry("completedAt", "1700000000000");
        }

        @Test
        void missionNameAndTitleNameAreDistinctKeys() {
            Map<String, String> m = data(0L, "주식왕");

            assertThat(m).containsEntry("missionName", "첫 거래");
            assertThat(m).containsEntry("titleName", "주식왕");
        }
    }
}
