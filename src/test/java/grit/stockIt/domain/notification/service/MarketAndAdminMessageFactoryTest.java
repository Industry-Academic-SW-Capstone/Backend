package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.notification.enums.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MarketNotificationMessageFactory} 와 {@link AdminNotificationMessageFactory} 단위 테스트.
 * 둘 다 협력자 0개이고 페이로드 맵 조립만 담당하므로 한 파일에서 다룬다.
 */
class MarketAndAdminMessageFactoryTest {

    @Nested
    @DisplayName("Market: 문구 상수")
    class MarketConstants {

        @Test
        void openTexts() {
            assertThat(MarketNotificationMessageFactory.MARKET_OPEN_TITLE).isEqualTo("장 시작 알림");
            assertThat(MarketNotificationMessageFactory.MARKET_OPEN_MESSAGE)
                    .isEqualTo("주식 시장이 시작되었습니다. 오늘도 좋은 하루 되세요!");
            assertThat(MarketNotificationMessageFactory.MARKET_OPEN_ICON).isEqualTo("market_open");
        }

        @Test
        void closeTexts() {
            assertThat(MarketNotificationMessageFactory.MARKET_CLOSE_TITLE).isEqualTo("장 마감 30분 전");
            assertThat(MarketNotificationMessageFactory.MARKET_CLOSE_MESSAGE)
                    .isEqualTo("장이 30분 후에 마감됩니다.");
            assertThat(MarketNotificationMessageFactory.MARKET_CLOSE_ICON).isEqualTo("market_close");
        }

        /**
         * 시작과 마감 문구가 서로 달라야 한다. 상수 교체 실수를 잡는다.
         */
        @Test
        void openAndCloseTextsAreDistinct() {
            assertThat(MarketNotificationMessageFactory.MARKET_OPEN_TITLE)
                    .isNotEqualTo(MarketNotificationMessageFactory.MARKET_CLOSE_TITLE);
            assertThat(MarketNotificationMessageFactory.MARKET_OPEN_MESSAGE)
                    .isNotEqualTo(MarketNotificationMessageFactory.MARKET_CLOSE_MESSAGE);
            assertThat(MarketNotificationMessageFactory.MARKET_OPEN_ICON)
                    .isNotEqualTo(MarketNotificationMessageFactory.MARKET_CLOSE_ICON);
        }
    }

    @Nested
    @DisplayName("Market: detailMap 2키")
    class MarketDetailMap {

        @Test
        void hasExactly2Keys() {
            Map<String, Object> m = MarketNotificationMessageFactory.detailMap(
                    NotificationType.MARKET_OPEN, 1700000000000L);

            assertThat(m).hasSize(2);
            assertThat(m.keySet()).containsExactlyInAnyOrder("type", "sentAt");
        }

        @Test
        void isHashMap() {
            assertThat(MarketNotificationMessageFactory.detailMap(NotificationType.MARKET_OPEN, 1L))
                    .isExactlyInstanceOf(HashMap.class);
        }

        @Test
        void usesProvidedNowMillis() {
            assertThat(MarketNotificationMessageFactory.detailMap(NotificationType.MARKET_OPEN, 1700000000000L))
                    .containsEntry("sentAt", 1700000000000L);
        }

        @Test
        void typeIsStoredAsName() {
            assertThat(MarketNotificationMessageFactory.detailMap(NotificationType.MARKET_CLOSE_REMINDER, 1L))
                    .containsEntry("type", "MARKET_CLOSE_REMINDER");
        }
    }

    @Nested
    @DisplayName("Market: fcmData 4키")
    class MarketFcmData {

        private Map<String, String> data() {
            return MarketNotificationMessageFactory.fcmData(
                    "장 시작 알림", "본문입니다", NotificationType.MARKET_OPEN, 1700000000000L);
        }

        @Test
        void hasExactly4Keys() {
            assertThat(data()).hasSize(4);
            assertThat(data().keySet()).containsExactlyInAnyOrder("title", "body", "type", "sentAt");
        }

        @Test
        void isHashMap() {
            assertThat(data()).isExactlyInstanceOf(HashMap.class);
        }

        /**
         * title 과 body 가 각자 자리에 들어간다. 두 값을 교체하는 뮤테이션이 여기서 잡힌다.
         */
        @Test
        void titleAndBodyAreNotSwapped() {
            assertThat(data()).containsEntry("title", "장 시작 알림");
            assertThat(data()).containsEntry("body", "본문입니다");
        }

        @Test
        void usesProvidedNowMillis() {
            assertThat(data()).containsEntry("sentAt", "1700000000000");
        }

        /**
         * detailMap 과 fcmData 에 같은 nowMillis 를 넘기면 두 sentAt 이 같은 값이 된다.
         * 이것이 C6 수정의 계약이다.
         */
        @Test
        void sameNowMillisProducesSameSentAtInBothMaps() {
            long now = 1700000000000L;
            Object detailSentAt = MarketNotificationMessageFactory
                    .detailMap(NotificationType.MARKET_OPEN, now).get("sentAt");
            String fcmSentAt = MarketNotificationMessageFactory
                    .fcmData("t", "b", NotificationType.MARKET_OPEN, now).get("sentAt");

            assertThat(String.valueOf(detailSentAt)).isEqualTo(fcmSentAt);
        }
    }

    @Nested
    @DisplayName("Admin: detailMap 4키, fcmData 4키, 타임스탬프 키 이름이 다르다")
    class AdminMaps {

        @Test
        void detailMapHasExactly4Keys() {
            Map<String, Object> m = AdminNotificationMessageFactory.detailMap("제목", "본문", 1700000000000L);

            assertThat(m).hasSize(4);
            assertThat(m.keySet()).containsExactlyInAnyOrder("title", "body", "type", "sentAt");
        }

        @Test
        void fcmDataHasExactly4Keys() {
            Map<String, String> m = AdminNotificationMessageFactory.fcmData("제목", "본문", 1700000000000L);

            assertThat(m).hasSize(4);
            assertThat(m.keySet()).containsExactlyInAnyOrder("title", "body", "type", "timestamp");
        }

        /**
         * 현행 동작: detailMap 은 sentAt, fcmData 는 timestamp 를 쓴다.
         * 키 이름 통일은 API 계약 변경이라 미결 항목이다.
         */
        @Test
        void timestampKeyNamesDifferBetweenMaps() {
            Map<String, Object> detail = AdminNotificationMessageFactory.detailMap("제목", "본문", 1L);
            Map<String, String> fcm = AdminNotificationMessageFactory.fcmData("제목", "본문", 1L);

            assertThat(detail).containsKey("sentAt").doesNotContainKey("timestamp");
            assertThat(fcm).containsKey("timestamp").doesNotContainKey("sentAt");
        }

        @Test
        void bothAreHashMaps() {
            assertThat(AdminNotificationMessageFactory.detailMap("t", "b", 1L))
                    .isExactlyInstanceOf(HashMap.class);
            assertThat(AdminNotificationMessageFactory.fcmData("t", "b", 1L))
                    .isExactlyInstanceOf(HashMap.class);
        }

        @Test
        void typeIsSystem() {
            assertThat(AdminNotificationMessageFactory.detailMap("t", "b", 1L))
                    .containsEntry("type", "SYSTEM");
            assertThat(AdminNotificationMessageFactory.fcmData("t", "b", 1L))
                    .containsEntry("type", "SYSTEM");
        }

        @Test
        void titleAndBodyArePassedThrough() {
            assertThat(AdminNotificationMessageFactory.fcmData("공지 제목", "공지 본문", 1L))
                    .containsEntry("title", "공지 제목")
                    .containsEntry("body", "공지 본문");
        }

        /**
         * 두 맵에 서로 다른 nowMillis 를 넘길 수 있다. 현행 호출부가 그렇게 동작한다.
         * 통일은 미결 항목이므로 이 자유도를 고정해둔다.
         */
        @Test
        void acceptsIndependentNowMillisValues() {
            assertThat(AdminNotificationMessageFactory.detailMap("t", "b", 100L)).containsEntry("sentAt", 100L);
            assertThat(AdminNotificationMessageFactory.fcmData("t", "b", 200L)).containsEntry("timestamp", "200");
        }
    }
}
