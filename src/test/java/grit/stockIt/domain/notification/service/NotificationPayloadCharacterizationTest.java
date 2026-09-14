package grit.stockIt.domain.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.enums.NotificationType;
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.domain.notification.event.MissionCompletedEvent;
import grit.stockIt.domain.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * notification 도메인 4개 서비스의 페이로드 골격을 고정하는 불변 특성화 파일 #2.
 *
 * <p><b>이 파일은 태그 {@code notification-charac-base} 이후 절대 수정하지 않는다.</b>
 * 완료 기준: {@code git log --oneline notification-charac-base..HEAD -- <이 파일>} 이 0 커밋.
 *
 * <h2>불변 파일 내용 규칙</h2>
 * <ul>
 *   <li><b>R1</b> — {@code %d}/{@code %,d} 포맷 출력을 포함하는 문자열은 어떤 형태로도 이 파일에
 *       넣지 않는다. C5 의 로케일 고정이 그 기대값을 무효화하기 때문이다. 구체적으로:
 *       <ul>
 *         <li>Mission 문구 단정은 {@code moneyAmount == 0} 경로로만 한정한다.
 *             {@code moneyAmount > 0} 조합은 {@code "보상으로 %,d원을..."} 을 포함하므로 전부 동결 파일 소유.</li>
 *         <li>Execution BUY/SELL 매핑은 <b>title 로만</b> 관측한다.
 *             body 는 {@code "%s %d주가 %s원에..."} 로 {@code %d} 와 {@code %,d} 를 함께 포함한다.</li>
 *         <li>Mission FCM data 의 {@code moneyAmount} 값 단정도 하지 않는다(R1 일관성).</li>
 *       </ul></li>
 *   <li><b>R2</b> — DB/FCM 값 일치 단정과 Market {@code sentAt} 값 단정은 동결 파일 단독 소유.</li>
 *   <li><b>R3</b> — 프로덕션 타입 + JUnit/Mockito/AssertJ 에만 의존한다.</li>
 *   <li><b>R4</b> — {@code ObjectMapper} 는 {@code spy(new ObjectMapper())} 를 쓴다.
 *       순수 목은 {@code writeValueAsString} 이 {@code null} 을 반환해 직렬화 보존 오라클이
 *       null 대 null 로 조용히 통과한다. 그 공허 통과는 green 이라 C1 게이트가 잡지 못하고
 *       C2 에서 영구 동결되어 FS-4 가 무효화된다.</li>
 * </ul>
 *
 * <h2>직렬화 보존 오라클</h2>
 * {@code detailData} 는 {@code NotificationResponse} 에서 {@code @JsonRawValue} 로 API 응답에
 * 그대로 실린다. 따라서 맵 구현이 {@code HashMap} 에서 {@code LinkedHashMap}/{@code Map.of} 로
 * 바뀌면 <b>키 집합은 그대로인데 JSON 문자열 순서가 바뀐다</b>. C3 가 4개 서비스를 전부 재작성하므로
 * 이 오라클이 없으면 최대 변경에 대한 가드가 "3파일 green" 뿐이 된다.
 * Execution/Mission 은 detailMap 에 타임스탬프가 없어 완전 문자열 동등이 가능하고,
 * Market/Admin 은 {@code sentAt} 을 포함하므로 맵 구현과 키 순서를 대신 고정한다.
 */
class NotificationPayloadCharacterizationTest {

    private static Member memberWithToken(long id, String token) {
        Member member = mock(Member.class);
        when(member.getMemberId()).thenReturn(id);
        when(member.hasFcmToken()).thenReturn(true);
        when(member.getFcmToken()).thenReturn(token);
        when(member.isExecutionNotificationEnabled()).thenReturn(true);
        return member;
    }

    private static ExecutionFilledEvent executionEvent(String orderMethod) {
        return new ExecutionFilledEvent(
                1L, 2L, 3L, 100L, 4L, "테스트대회",
                "005930", "삼성전자",
                new BigDecimal("70000"), 10, orderMethod);
    }

    /** moneyAmount == 0, titleName == null → R1 안전 경로 */
    private static MissionCompletedEvent missionEventNoReward(MissionTrack track) {
        return new MissionCompletedEvent(100L, 200L, "첫 거래", track, 300L, 0L, null);
    }

    /** moneyAmount == 0, titleName != null → 칭호 문구만 나오는 R1 안전 경로 */
    private static MissionCompletedEvent missionEventTitleOnly() {
        return new MissionCompletedEvent(100L, 200L, "첫 거래", MissionTrack.DAILY, 300L, 0L, "주식왕");
    }

    // ---------------------------------------------------------------------
    // 캡처 하네스
    // ---------------------------------------------------------------------

    private record Captured(Notification notification, Map<String, String> fcmData) { }

    @SuppressWarnings("unchecked")
    private static Captured runExecution(ExecutionFilledEvent event) {
        MemberRepository memberRepository = mock(MemberRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());   // R4
        FcmService fcmService = mock(FcmService.class);
        Member targetMember = memberWithToken(100L, "t");
            when(memberRepository.findById(100L)).thenReturn(Optional.of(targetMember));

        List<Notification> saved = new ArrayList<>();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        List<Map<String, String>> sent = new ArrayList<>();
        when(fcmService.sendExecutionNotification(anyString(), any())).thenAnswer(inv -> {
            sent.add((Map<String, String>) inv.getArgument(1));
            return true;
        });

        new ExecutionNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                .handleExecutionFilledEvent(event);

        return new Captured(saved.get(0), sent.get(0));
    }

    @SuppressWarnings("unchecked")
    private static Captured runMission(MissionCompletedEvent event) {
        MemberRepository memberRepository = mock(MemberRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());   // R4
        FcmService fcmService = mock(FcmService.class);
        Member targetMember = memberWithToken(100L, "t");
            when(memberRepository.findById(100L)).thenReturn(Optional.of(targetMember));

        List<Notification> saved = new ArrayList<>();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        List<Map<String, String>> sent = new ArrayList<>();
        when(fcmService.sendExecutionNotification(anyString(), any())).thenAnswer(inv -> {
            sent.add((Map<String, String>) inv.getArgument(1));
            return true;
        });

        new MissionNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                .handleMissionCompletedEvent(event);

        return new Captured(saved.get(0), sent.get(0));
    }

    @SuppressWarnings("unchecked")
    private static Captured runMarketOpen() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());   // R4
        FcmService fcmService = mock(FcmService.class);
        Member member0 = memberWithToken(1L, "t1");
            when(memberRepository.findAll()).thenReturn(List.of(member0));

        List<Notification> saved = new ArrayList<>();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        List<Map<String, String>> sent = new ArrayList<>();
        when(fcmService.sendExecutionNotification(anyString(), any())).thenAnswer(inv -> {
            sent.add((Map<String, String>) inv.getArgument(1));
            return true;
        });

        new MarketNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                .sendMarketOpenNotification();

        return new Captured(saved.get(0), sent.get(0));
    }

    @SuppressWarnings("unchecked")
    private static Captured runMarketClose() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());
        FcmService fcmService = mock(FcmService.class);
        Member member0 = memberWithToken(1L, "t1");
            when(memberRepository.findAll()).thenReturn(List.of(member0));

        List<Notification> saved = new ArrayList<>();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        List<Map<String, String>> sent = new ArrayList<>();
        when(fcmService.sendExecutionNotification(anyString(), any())).thenAnswer(inv -> {
            sent.add((Map<String, String>) inv.getArgument(1));
            return true;
        });

        new MarketNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                .sendMarketCloseReminderNotification();

        return new Captured(saved.get(0), sent.get(0));
    }

    @SuppressWarnings("unchecked")
    private static Captured runAdmin(String title, String body) {
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());
        FcmService fcmService = mock(FcmService.class);

        List<Notification> saved = new ArrayList<>();
        when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        List<Map<String, String>> sent = new ArrayList<>();
        when(fcmService.sendExecutionNotification(anyString(), any())).thenAnswer(inv -> {
            sent.add((Map<String, String>) inv.getArgument(1));
            return true;
        });

        new AdminNotificationService(fcmService, notificationRepository, objectMapper)
                .sendBroadcastNotification(List.of(memberWithToken(1L, "t1")), title, body);

        return new Captured(saved.get(0), sent.get(0));
    }

    // =====================================================================
    // FCM data 키 집합 — 정확한 크기와 구성
    //   Execution 14 / Mission 10 / Market 4 / Admin 4
    //   M4(마지막 원소 드롭)와 M6(map 키 교체)이 이 단정으로 red 가 된다.
    // =====================================================================

    @Nested
    @DisplayName("FCM data 키 집합이 정확히 고정된다")
    class FcmDataKeySets {

        @Test
        void execution_fcmDataHasExactly14Keys() {
            Map<String, String> data = runExecution(executionEvent("BUY")).fcmData();

            assertThat(data).hasSize(14);
            assertThat(data.keySet()).containsExactlyInAnyOrder(
                    "title", "body", "type",
                    "executionId", "orderId", "accountId",
                    "contestId", "contestName",
                    "stockCode", "stockName",
                    "price", "quantity", "orderMethod",
                    "executedAt");
        }

        @Test
        void mission_fcmDataHasExactly10Keys() {
            Map<String, String> data = runMission(missionEventNoReward(MissionTrack.DAILY)).fcmData();

            assertThat(data).hasSize(10);
            assertThat(data.keySet()).containsExactlyInAnyOrder(
                    "title", "body", "type",
                    "missionId", "missionName", "track",
                    "rewardId", "moneyAmount", "titleName",
                    "completedAt");
        }

        @Test
        void market_fcmDataHasExactly4Keys() {
            Map<String, String> data = runMarketOpen().fcmData();

            assertThat(data).hasSize(4);
            assertThat(data.keySet()).containsExactlyInAnyOrder("title", "body", "type", "sentAt");
        }

        @Test
        void admin_fcmDataHasExactly4Keys() {
            Map<String, String> data = runAdmin("제목", "본문").fcmData();

            assertThat(data).hasSize(4);
            assertThat(data.keySet()).containsExactlyInAnyOrder("title", "body", "type", "timestamp");
        }
    }

    // =====================================================================
    // detailData 키 집합 — Execution 10 / Mission 6 / Market 2 / Admin 4
    // =====================================================================

    @Nested
    @DisplayName("detailData 키 집합이 정확히 고정된다")
    class DetailDataKeySets {

        private Map<String, Object> parse(String json) throws Exception {
            return new ObjectMapper().readValue(json, Map.class);
        }

        @Test
        void execution_detailDataHasExactly10Keys() throws Exception {
            Map<String, Object> detail = parse(runExecution(executionEvent("BUY")).notification().getDetailData());

            assertThat(detail).hasSize(10);
            assertThat(detail.keySet()).containsExactlyInAnyOrder(
                    "executionId", "orderId", "accountId",
                    "contestId", "contestName",
                    "stockCode", "stockName",
                    "price", "quantity", "orderMethod");
        }

        @Test
        void mission_detailDataHasExactly6Keys() throws Exception {
            Map<String, Object> detail = parse(
                    runMission(missionEventNoReward(MissionTrack.DAILY)).notification().getDetailData());

            assertThat(detail).hasSize(6);
            assertThat(detail.keySet()).containsExactlyInAnyOrder(
                    "missionId", "missionName", "track",
                    "rewardId", "moneyAmount", "titleName");
        }

        @Test
        void market_detailDataHasExactly2Keys() throws Exception {
            Map<String, Object> detail = parse(runMarketOpen().notification().getDetailData());

            assertThat(detail).hasSize(2);
            assertThat(detail.keySet()).containsExactlyInAnyOrder("type", "sentAt");
        }

        @Test
        void admin_detailDataHasExactly4Keys() throws Exception {
            Map<String, Object> detail = parse(runAdmin("제목", "본문").notification().getDetailData());

            assertThat(detail).hasSize(4);
            assertThat(detail.keySet()).containsExactlyInAnyOrder("title", "body", "type", "sentAt");
        }
    }

    // =====================================================================
    // 타임스탬프 키 이름 — 값은 단정하지 않는다
    //   4개 서비스가 서로 다른 키 이름을 쓴다. 통일은 미결 항목이므로 현 상태를 고정한다.
    // =====================================================================

    @Nested
    @DisplayName("타임스탬프 키 이름이 서비스마다 다르게 고정된다")
    class TimestampKeyNames {

        @Test
        void execution_usesExecutedAt() {
            assertThat(runExecution(executionEvent("BUY")).fcmData()).containsKey("executedAt");
        }

        @Test
        void mission_usesCompletedAt() {
            assertThat(runMission(missionEventNoReward(MissionTrack.DAILY)).fcmData()).containsKey("completedAt");
        }

        @Test
        void market_usesSentAtInBothPaths() throws Exception {
            Captured c = runMarketOpen();
            assertThat(c.fcmData()).containsKey("sentAt");
            assertThat(new ObjectMapper().readValue(c.notification().getDetailData(), Map.class))
                    .containsKey("sentAt");
        }

        @Test
        void admin_usesTimestampInFcmAndSentAtInDetail() throws Exception {
            Captured c = runAdmin("제목", "본문");
            assertThat(c.fcmData()).containsKey("timestamp");
            assertThat(new ObjectMapper().readValue(c.notification().getDetailData(), Map.class))
                    .containsKey("sentAt");
        }
    }

    // =====================================================================
    // type 값과 iconType
    //   M5(switch case 교체)가 iconType 파라미터화 단정으로 red 가 된다.
    // =====================================================================

    @Nested
    @DisplayName("type 값과 iconType 이 고정된다")
    class TypeAndIconType {

        @Test
        void execution_typeAndIconType() {
            Captured c = runExecution(executionEvent("BUY"));
            assertThat(c.fcmData()).containsEntry("type", "EXECUTION");
            assertThat(c.notification().getType()).isEqualTo(NotificationType.EXECUTION);
            assertThat(c.notification().getIconType()).isEqualTo("execution_success");
        }

        @Test
        void mission_type() {
            Captured c = runMission(missionEventNoReward(MissionTrack.DAILY));
            assertThat(c.fcmData()).containsEntry("type", "MISSION_COMPLETED");
            assertThat(c.notification().getType()).isEqualTo(NotificationType.MISSION_COMPLETED);
        }

        /**
         * MissionTrack 5개 매핑 전부. M5(SWING/LONG_TERM 반환값 교체)가 여기서 red 가 된다.
         *
         * <p>주의: 이 단정은 iconType 매핑을 고정할 뿐이며,
         * "MissionTrack 추가"를 어느 클래스의 변경 축으로 선언하지 않는다.
         * MissionTrack 참조는 mission 도메인 전역 29곳이고 컴파일러가 막는 곳은 exhaustive switch 1곳뿐이다.
         */
        @ParameterizedTest
        @CsvSource({
                "DAILY,       mission_daily",
                "SHORT_TERM,  mission_short_term",
                "SWING,       mission_swing",
                "LONG_TERM,   mission_long_term",
                "ACHIEVEMENT, mission_achievement"
        })
        void mission_iconTypeMapping(MissionTrack track, String expectedIcon) {
            Captured c = runMission(missionEventNoReward(track));
            assertThat(c.notification().getIconType()).isEqualTo(expectedIcon);
        }

        @Test
        void market_openTypeAndIconType() {
            Captured c = runMarketOpen();
            assertThat(c.fcmData()).containsEntry("type", "MARKET_OPEN");
            assertThat(c.notification().getType()).isEqualTo(NotificationType.MARKET_OPEN);
            assertThat(c.notification().getIconType()).isEqualTo("market_open");
        }

        @Test
        void market_closeTypeAndIconType() {
            Captured c = runMarketClose();
            assertThat(c.fcmData()).containsEntry("type", "MARKET_CLOSE_REMINDER");
            assertThat(c.notification().getType()).isEqualTo(NotificationType.MARKET_CLOSE_REMINDER);
            assertThat(c.notification().getIconType()).isEqualTo("market_close");
        }

        @Test
        void admin_typeAndIconType() {
            Captured c = runAdmin("제목", "본문");
            assertThat(c.fcmData()).containsEntry("type", "SYSTEM");
            assertThat(c.notification().getType()).isEqualTo(NotificationType.SYSTEM);
            assertThat(c.notification().getIconType()).isEqualTo("system");
        }
    }

    // =====================================================================
    // 로케일 무관 문구 (R1 준수)
    //   %d / %,d 출력을 포함하지 않는 문구만 여기서 고정한다.
    //   M3(BUY/SELL 조건 반전)과 M7(title/body 교체)이 여기서 red 가 된다.
    // =====================================================================

    @Nested
    @DisplayName("로케일 무관 문구가 고정된다")
    class LocaleIndependentText {

        /**
         * R1: BUY/SELL 매핑은 title 로만 관측한다.
         * body 는 "%s %d주가 %s원에 체결되었습니다" 로 %d 와 %,d 를 함께 포함하므로 동결 파일 소유다.
         * M3(조건 반전)이 이 단정으로 red 가 된다.
         */
        @Test
        void execution_buyTitle() {
            assertThat(runExecution(executionEvent("BUY")).notification().getTitle())
                    .isEqualTo("삼성전자 매수 체결");
        }

        @Test
        void execution_sellTitle() {
            assertThat(runExecution(executionEvent("SELL")).notification().getTitle())
                    .isEqualTo("삼성전자 매도 체결");
        }

        @Test
        void mission_title() {
            assertThat(runMission(missionEventNoReward(MissionTrack.DAILY)).notification().getTitle())
                    .isEqualTo("첫 거래 완료!");
        }

        /**
         * R1 안전 경로: moneyAmount == 0 && titleName == null 이면 %,d 가 출력되지 않는다.
         * M1(moneyAmount > 0 을 >= 0 으로)과 M2'(선행 공백 주입)가 여기서 red 가 된다.
         */
        @Test
        void mission_noRewardMessage() {
            assertThat(runMission(missionEventNoReward(MissionTrack.DAILY)).notification().getMessage())
                    .isEqualTo("미션을 완료했습니다!");
        }

        /**
         * R1 안전 경로: moneyAmount == 0 && titleName != null 이면 칭호 문구만 나온다.
         * M2'(builder.length() > 0 을 >= 0 으로)가 선행 공백을 주입해 여기서 red 가 된다.
         */
        @Test
        void mission_titleOnlyMessage() {
            assertThat(runMission(missionEventTitleOnly()).notification().getMessage())
                    .isEqualTo("칭호 '주식왕'를 획득했습니다.");
        }

        /**
         * M7(title/body 교체)이 이 두 단정으로 red 가 된다.
         */
        @Test
        void market_openTitleAndMessage() {
            Captured c = runMarketOpen();
            assertThat(c.notification().getTitle()).isEqualTo("장 시작 알림");
            assertThat(c.notification().getMessage()).isEqualTo("주식 시장이 시작되었습니다. 오늘도 좋은 하루 되세요!");
            assertThat(c.fcmData()).containsEntry("title", "장 시작 알림");
            assertThat(c.fcmData()).containsEntry("body", "주식 시장이 시작되었습니다. 오늘도 좋은 하루 되세요!");
        }

        @Test
        void market_closeTitleAndMessage() {
            Captured c = runMarketClose();
            assertThat(c.notification().getTitle()).isEqualTo("장 마감 30분 전");
            assertThat(c.notification().getMessage()).isEqualTo("장이 30분 후에 마감됩니다.");
        }

        @Test
        void admin_titleAndBodyPassThrough() {
            Captured c = runAdmin("점검 공지", "오늘 밤 점검이 있습니다.");
            assertThat(c.notification().getTitle()).isEqualTo("점검 공지");
            assertThat(c.notification().getMessage()).isEqualTo("오늘 밤 점검이 있습니다.");
            assertThat(c.fcmData()).containsEntry("title", "점검 공지");
            assertThat(c.fcmData()).containsEntry("body", "오늘 밤 점검이 있습니다.");
        }
    }

    // =====================================================================
    // null 정책 — 두 맵의 정책이 다르다
    //   FCM data 는 빈 문자열로 치환하고 detailMap 은 null 을 유지한다.
    // =====================================================================

    @Nested
    @DisplayName("null 정책이 FCM data 와 detailMap 에서 다르게 고정된다")
    class NullPolicy {

        @Test
        void mission_nullTitleName_becomesEmptyStringInFcmButNullInDetail() throws Exception {
            Captured c = runMission(missionEventNoReward(MissionTrack.DAILY));

            assertThat(c.fcmData()).containsEntry("titleName", "");

            Map<String, Object> detail = new ObjectMapper()
                    .readValue(c.notification().getDetailData(), Map.class);
            assertThat(detail).containsKey("titleName");
            assertThat(detail.get("titleName")).isNull();
        }

        @Test
        void mission_nullRewardId_becomesEmptyStringInFcm() {
            MissionCompletedEvent event = new MissionCompletedEvent(
                    100L, 200L, "첫 거래", MissionTrack.DAILY, null, 0L, null);

            assertThat(runMission(event).fcmData()).containsEntry("rewardId", "");
        }

        @Test
        void mission_presentRewardId_isStringified() {
            assertThat(runMission(missionEventNoReward(MissionTrack.DAILY)).fcmData())
                    .containsEntry("rewardId", "300");
        }
    }

    // =====================================================================
    // 직렬화 보존 오라클 (FS-4 가드)
    //
    //   R4 의 spy 하네스 덕분에 캡처된 getDetailData() 가 실제 JSON 이며
    //   null 대 null 공허 통과가 구조적으로 배제된다.
    //
    //   Execution/Mission: detailMap 에 타임스탬프가 없어 완전 문자열 동등이 가능하다.
    //   Market/Admin: sentAt 을 포함하므로 맵 구현과 키 순서를 대신 고정한다.
    // =====================================================================

    @Nested
    @DisplayName("직렬화된 detailData 가 보존된다 (FS-4 가드)")
    class SerializationPreservation {

        /**
         * 이 단정이 red 가 되면 맵 구현이 HashMap 에서 바뀌었다는 뜻이다.
         * detailData 는 @JsonRawValue 로 API 응답에 그대로 실리므로 순서 변경은 사용자 노출 변경이다.
         */
        @Test
        void execution_detailDataSerializesToExactString() {
            String detailData = runExecution(executionEvent("BUY")).notification().getDetailData();

            assertThat(detailData).isNotNull();
            assertThat(detailData).isNotEqualTo("null");
            assertThat(detailData).isEqualTo(
                    "{\"executionId\":1,\"accountId\":3,\"contestId\":4,\"stockName\":\"삼성전자\","
                            + "\"quantity\":10,\"orderId\":2,\"price\":70000,\"contestName\":\"테스트대회\","
                            + "\"stockCode\":\"005930\",\"orderMethod\":\"BUY\"}");
        }

        @Test
        void mission_detailDataSerializesToExactString() {
            String detailData = runMission(missionEventNoReward(MissionTrack.DAILY))
                    .notification().getDetailData();

            assertThat(detailData).isNotNull();
            assertThat(detailData).isNotEqualTo("null");
            assertThat(detailData).isEqualTo(
                    "{\"rewardId\":300,\"missionId\":200,\"titleName\":null,"
                            + "\"missionName\":\"첫 거래\",\"track\":\"DAILY\",\"moneyAmount\":0}");
        }

        /**
         * Market/Admin 은 sentAt 값이 매 실행 달라지므로 문자열 동등 대신
         * 맵 구현과 키 순서를 고정한다. 순서는 HashMap 의 해시 순서에 의존하며
         * 구현이 바뀌면 이 단정이 red 가 된다.
         */
        @Test
        void market_detailDataKeyOrderIsHashMapOrder() throws Exception {
            String detailData = runMarketOpen().notification().getDetailData();

            assertThat(detailData).isNotNull();
            assertThat(detailData).isNotEqualTo("null");

            Map<String, Object> reference = new HashMap<>();
            reference.put("type", "MARKET_OPEN");
            reference.put("sentAt", 0L);

            Map<String, Object> parsed = new ObjectMapper().readValue(detailData, Map.class);
            assertThat(new ArrayList<>(parsed.keySet()))
                    .isEqualTo(new ArrayList<>(reference.keySet()));
        }

        @Test
        void admin_detailDataKeyOrderIsHashMapOrder() throws Exception {
            String detailData = runAdmin("제목", "본문").notification().getDetailData();

            assertThat(detailData).isNotNull();
            assertThat(detailData).isNotEqualTo("null");

            Map<String, Object> reference = new HashMap<>();
            reference.put("title", "제목");
            reference.put("body", "본문");
            reference.put("type", "SYSTEM");
            reference.put("sentAt", 0L);

            Map<String, Object> parsed = new ObjectMapper().readValue(detailData, Map.class);
            assertThat(new ArrayList<>(parsed.keySet()))
                    .isEqualTo(new ArrayList<>(reference.keySet()));
        }
    }

    // =====================================================================
    // FCM 값 형태 — 로케일 무관한 것만
    // =====================================================================

    @Nested
    @DisplayName("FCM data 의 로케일 무관 값이 고정된다")
    class LocaleIndependentFcmValues {

        @Test
        void execution_identifiersAreStringified() {
            Map<String, String> data = runExecution(executionEvent("BUY")).fcmData();

            assertThat(data).containsEntry("executionId", "1");
            assertThat(data).containsEntry("orderId", "2");
            assertThat(data).containsEntry("accountId", "3");
            assertThat(data).containsEntry("contestId", "4");
            assertThat(data).containsEntry("contestName", "테스트대회");
            assertThat(data).containsEntry("stockCode", "005930");
            assertThat(data).containsEntry("stockName", "삼성전자");
            assertThat(data).containsEntry("orderMethod", "BUY");
            assertThat(data).containsEntry("quantity", "10");
            assertThat(data).containsEntry("price", "70000");
        }

        @Test
        void mission_identifiersAreStringified() {
            Map<String, String> data = runMission(missionEventNoReward(MissionTrack.DAILY)).fcmData();

            assertThat(data).containsEntry("missionId", "200");
            assertThat(data).containsEntry("missionName", "첫 거래");
            assertThat(data).containsEntry("track", "DAILY");
            // moneyAmount 값 단정은 R1 일관성을 위해 하지 않는다
        }
    }
}
