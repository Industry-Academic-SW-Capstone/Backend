package grit.stockIt.domain.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.mission.enums.MissionTrack;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.event.ExecutionFilledEvent;
import grit.stockIt.domain.notification.event.MissionCompletedEvent;
import grit.stockIt.domain.notification.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 결함 동결 파일. <b>수정 커밋이 갱신하는 유일한 특성화 파일이다.</b>
 *
 * <p>불변 2파일({@code NotificationWiringCharacterizationTest},
 * {@code NotificationPayloadCharacterizationTest})은 태그 이후 수정 금지지만
 * 이 파일은 다르다. 여기 담긴 단정은 <b>고칠 예정인 현재 동작</b>이고,
 * 수정 커밋이 이 파일을 갱신하며 <b>그 diff 가 곧 사용자 영향 명세</b>다.
 *
 * <h2>왜 분리하는가</h2>
 * 고칠 동작이 불변 파일에 들어가면 규약 충돌이 일어난다. 수정 커밋이 불변 파일을 건드려야 하는데
 * 그것이 금지되어 있으므로 둘 중 하나를 반드시 어기게 된다. 그래서 결함 ①②③-a 가 관여하는
 * 모든 단정(값 비교, 로케일 의존 문자열, sentAt 값 관계)은 이 파일이 단독 소유한다.
 *
 * <h2>동결 케이스</h2>
 * <ul>
 *   <li><b>DF-1</b> — 결함 ①(메시지 생성 중복). DB 저장분과 FCM 푸시분이 현재 <b>각각 계산</b>되지만
 *       문자열이 동일함을 고정한다. C4(중복 제거) 이후에도 그대로 통과해야 하며,
 *       통과하는 것이 곧 "사용자 영향 없음"의 증거다(diff 0줄 예상).</li>
 *   <li><b>DF-2</b> — 결함 ②의 <b>대조군</b>. 배포 대상 로케일(ko-KR)에서는 수정 전후 출력이 같다.
 *       이 케이스만 있으면 수정 여부를 판별할 수 없으므로 DF-3 이 따로 필요하다.</li>
 *   <li><b>DF-3</b> — 결함 ②의 <b>판별 케이스</b>. 기본 로케일을 ar-SA 로 바꿔야만 회귀가 드러난다.
 *       <b>수량({@code %d})과 금액({@code %,d}) 둘 다</b> 단정한다.
 *       {@code formatPrice} 만 고치면 같은 문자열의 수량이 로케일 의존으로 남아
 *       ar-SA 에서 혼종 숫자 출력이 된다.
 *       <p><b>C5 에서 갱신됨.</b> 수정 전에는 ar-SA 에서 수량과 금액이 모두 아랍-인도 숫자였다
 *       ("매수 \u0661\u0660주가 \u0667\u0660\u066c\u0660\u0660\u0660원에 체결되었습니다").
 *       {@code String.format} 에 {@code Locale.ROOT} 를 명시한 뒤 둘 다 latn 으로 고정된다.
 *       <b>이 갱신 diff 가 사용자 영향 명세다: 비latn 로케일 사용자가 보던 숫자 표기가 바뀐다.</b>
 *       배포 대상이 ko-KR 이라 DF-2 대조군은 수정 유무와 무관하게 통과하므로 이 판별 케이스가 필요하다.</li>
 *   <li><b>DF-4</b> — 결함 ③-a(Market sentAt 이중 호출).
 *       <b>C6 에서 갱신됨.</b> 수정 전에는 DB 용과 FCM 용이 독립 호출이라 일치가 보장되지 않았고
 *       {@code detailSentAt <= fcmSentAt} 으로만 동결할 수 있었다.
 *       회원 루프 안에서 회원당 1회만 읽도록 바꾼 뒤 두 값이 항상 같아져 {@code isEqualTo} 로 조인다.
 *       <b>{@code <=} 에서 {@code ==} 로 바뀐 diff 가 사용자 영향 명세다:</b>
 *       같은 알림의 DB 기록 시각과 푸시 페이로드 시각이 더 이상 갈리지 않는다.
 *       JSON 키와 타입은 그대로이므로 API 계약 변경은 없다.</li>
 * </ul>
 *
 * <h2>DF-4 의 판별 케이스 (루프 안/밖 구분)</h2>
 * 단일 멤버 픽스처만으로는 부족하다. 캡처를 루프 <b>밖</b>으로 올려도 각 멤버 내부의 DB/FCM 일치는
 * 여전히 참이라 단일 멤버 단정이 전부 통과한다. 이것을 실측으로 확인했다:
 * 캡처를 루프 밖으로 hoist 했을 때 전체 232건이 green 이었다.
 *
 * <p>따라서 <b>여러 멤버가 서로 다른 {@code sentAt} 을 받는지</b>를 직접 단정한다.
 * 루프 안 캡처면 멤버마다 값이 갈릴 수 있고, 루프 밖 캡처면 전원이 같은 값을 공유한다.
 * 저장 스텁에 지연을 넣어 시각이 실제로 진행하도록 만든 뒤 첫 멤버와 마지막 멤버의 값을 비교한다.
 *
 * <p>소스 위치 확인(호출이 1개이고 루프 내부인지)은 여전히 유효한 보조 게이트이지만
 * 더 이상 <b>유일한</b> 판별자가 아니다.
 */
class NotificationDefectFreezeTest {

    private static Member memberWithToken(long id, String token) {
        Member member = mock(Member.class);
        when(member.getMemberId()).thenReturn(id);
        when(member.hasFcmToken()).thenReturn(true);
        when(member.getFcmToken()).thenReturn(token);
        when(member.isExecutionNotificationEnabled()).thenReturn(true);
        return member;
    }

    private record Captured(Notification notification, Map<String, String> fcmData) { }

    @SuppressWarnings("unchecked")
    private static Captured runExecution(ExecutionFilledEvent event) {
        MemberRepository memberRepository = mock(MemberRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        ObjectMapper objectMapper = spy(new ObjectMapper());
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
        ObjectMapper objectMapper = spy(new ObjectMapper());
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

    private static ExecutionFilledEvent executionEvent() {
        return new ExecutionFilledEvent(
                1L, 2L, 3L, 100L, 4L, "테스트대회",
                "005930", "삼성전자",
                new BigDecimal("70000"), 10, "BUY");
    }

    private static MissionCompletedEvent missionEventWithMoney() {
        return new MissionCompletedEvent(
                100L, 200L, "첫 거래", MissionTrack.DAILY, 300L, 50000L, null);
    }

    // =====================================================================
    // DF-1 — 결함 ①: 메시지 생성 중복
    //
    //   현재 DB 저장분과 FCM 푸시분이 각각 별도로 계산된다.
    //   지금은 값이 같지만 한쪽만 고치면 갈라진다.
    //   C4 가 중복을 제거해도 이 단정은 그대로 통과해야 하며,
    //   통과하는 것이 곧 "사용자 영향 없음"의 증거다.
    // =====================================================================

    @Nested
    @DisplayName("DF-1 (결함 ①): DB 저장분과 FCM 푸시분의 문구가 동일하다")
    class DefectMessageDuplication {

        @Test
        void df1_execution_dbAndFcmTitleAndBodyAreIdentical() {
            Captured c = runExecution(executionEvent());

            assertThat(c.notification().getTitle()).isEqualTo(c.fcmData().get("title"));
            assertThat(c.notification().getMessage()).isEqualTo(c.fcmData().get("body"));
        }

        @Test
        void df1_mission_dbAndFcmTitleAndBodyAreIdentical() {
            Captured c = runMission(missionEventWithMoney());

            assertThat(c.notification().getTitle()).isEqualTo(c.fcmData().get("title"));
            assertThat(c.notification().getMessage()).isEqualTo(c.fcmData().get("body"));
        }
    }

    // =====================================================================
    // DF-2 — 결함 ② 대조군
    //
    //   배포 대상 로케일(ko-KR)에서는 Locale.ROOT 고정 전후 출력이 동일하다.
    //   따라서 이 케이스만으로는 수정 여부를 판별할 수 없다. DF-3 이 판별자다.
    // =====================================================================

    @Nested
    @DisplayName("DF-2 (결함 ② 대조군): ko-KR 에서는 수정 전후 출력이 같다")
    class DefectLocaleControl {

        @Test
        void df2_execution_koKrBodyUsesLatinDigits() {
            Locale orig = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.KOREA);

                assertThat(runExecution(executionEvent()).notification().getMessage())
                        .isEqualTo("매수 10주가 70,000원에 체결되었습니다");
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, orig);
            }
        }

        @Test
        void df2_mission_koKrMessageUsesLatinDigits() {
            Locale orig = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.KOREA);

                assertThat(runMission(missionEventWithMoney()).notification().getMessage())
                        .isEqualTo("보상으로 50,000원을 받았습니다.");
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, orig);
            }
        }
    }

    // =====================================================================
    // DF-3 — 결함 ② 판별 케이스
    //
    //   기본 로케일을 ar-SA 로 바꿔야만 회귀가 드러난다.
    //   현재 동작(결함 상태)을 동결한다: 수량과 금액이 둘 다 Arabic-Indic 숫자가 된다.
    //
    //   C5 가 이 기대값을 latn 고정으로 갱신하며, 그 diff 가
    //   "비latn 로케일 사용자가 보던 문자열이 바뀐다"는 명세다.
    //
    //   중요: 수량(%d)과 금액(%,d)을 둘 다 단정한다. formatPrice 의 %,d 만 고치면
    //   같은 문자열의 수량이 로케일 의존으로 남아 혼종 출력이 된다.
    //   두 단정의 동시 갱신만이 완전한 수정의 증거다.
    // =====================================================================

    @Nested
    @DisplayName("DF-3 (결함 ② 판별): ar-SA 에서도 수량과 금액이 모두 latn 숫자다 (C5 수정 후)")
    class DefectLocaleDiscriminating {

        /**
         * C5 수정 후 동작. ar-SA 기본 로케일에서도 수량과 금액이 모두 latn 숫자다.
         *
         * <p>수정 전에는 "매수 \u0661\u0660주가 \u0667\u0660\u066c\u0660\u0660\u0660원에 체결되었습니다" 였다.
         * 이 한 줄의 변화가 사용자 영향 명세다.
         */
        @Test
        void df3_execution_arSaBodyUsesLatinQuantityAndPrice() {
            Locale orig = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-SA"));

                String message = runExecution(executionEvent()).notification().getMessage();

                // 수정 후: Locale.ROOT 고정으로 수량과 금액이 둘 다 latn
                assertThat(message).isEqualTo("매수 10주가 70,000원에 체결되었습니다");
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, orig);
            }
        }

        /**
         * 수량 단독 판별. formatPrice 만 고치는 부분 수정을 잡아낸다.
         *
         * <p>바깥 String.format 에 Locale.ROOT 를 누락하면 금액은 latn 인데 수량만
         * 아랍-인도 숫자로 남는 혼종 출력이 되고, 이 단정이 red 가 된다.
         */
        @Test
        void df3_execution_arSaQuantityAloneIsLatin() {
            Locale orig = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-SA"));

                assertThat(runExecution(executionEvent()).notification().getMessage())
                        .contains("10주가")
                        .doesNotContain("\u0661\u0660주가");
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, orig);
            }
        }

        @Test
        void df3_mission_arSaMoneyIsLatin() {
            Locale orig = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-SA"));

                assertThat(runMission(missionEventWithMoney()).notification().getMessage())
                        .isEqualTo("보상으로 50,000원을 받았습니다.");
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, orig);
            }
        }

        /**
         * FCM 푸시분도 같은 수정을 공유한다. C4 로 계산 지점이 하나가 됐으므로
         * 한쪽만 고쳐질 수 없다.
         */
        @Test
        void df3_execution_arSaFcmBodySharesSameFix() {
            Locale orig = Locale.getDefault(Locale.Category.FORMAT);
            try {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-SA"));

                Captured c = runExecution(executionEvent());

                assertThat(c.fcmData().get("body"))
                        .isEqualTo("매수 10주가 70,000원에 체결되었습니다");
                // 두 경로가 같은 값을 공유함 = DF-1 과 정합
                assertThat(c.fcmData().get("body")).isEqualTo(c.notification().getMessage());
            } finally {
                Locale.setDefault(Locale.Category.FORMAT, orig);
            }
        }
    }

    // =====================================================================
    // DF-4 — 결함 ③-a: Market sentAt 이중 호출
    //
    //   현재 DB 용 sentAt 과 FCM 용 sentAt 이 각각 독립 호출이라 일치가 보장되지 않는다.
    //   두 호출 사이에 notificationRepository.save 가 끼어 있어 DB I/O 만큼 값이 벌어진다.
    //
    //   detailSentAt <= fcmSentAt 으로 동결한다(현재는 항상 참, 결정론적).
    //   C6 이 isEqualTo 로 갱신한다. <= 에서 == 로 바뀌는 diff 가 사용자 영향 명세다.
    //
    //   JSON 키와 타입은 diff 에 나타나지 않는다 = API 계약 변경 없음의 증거.
    // =====================================================================

    @Nested
    @DisplayName("DF-4 (결함 ③-a): Market 의 DB sentAt 과 FCM sentAt 이 같은 값이다 (C6 수정 후)")
    class DefectMarketSentAt {

        @SuppressWarnings("unchecked")
        private Captured runMarketSingleMember() {
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
                    .sendMarketOpenNotification();

            return new Captured(saved.get(0), sent.get(0));
        }

        /**
         * C6 수정 후: 회원당 1회만 읽은 값을 두 경로가 공유하므로 항상 같다.
         *
         * <p>수정 전에는 두 독립 호출 사이에 DB I/O 가 끼어 값이 벌어질 수 있었고
         * {@code isLessThanOrEqualTo} 로만 동결할 수 있었다.
         */
        @Test
        void df4_market_detailSentAtEqualsFcmSentAt() throws Exception {
            Captured c = runMarketSingleMember();

            Map<String, Object> detail = new ObjectMapper()
                    .readValue(c.notification().getDetailData(), Map.class);

            long detailSentAt = ((Number) detail.get("sentAt")).longValue();
            long fcmSentAt = Long.parseLong(c.fcmData().get("sentAt"));

            assertThat(detailSentAt).isEqualTo(fcmSentAt);
        }

        /**
         * 키와 타입은 결함 수정과 무관하게 유지된다. C6 diff 에 나타나지 않아야 한다.
         * 이것이 "API 계약 변경 없음"의 증거다.
         */
        @Test
        void df4_market_sentAtKeyAndTypeAreStable() throws Exception {
            Captured c = runMarketSingleMember();

            Map<String, Object> detail = new ObjectMapper()
                    .readValue(c.notification().getDetailData(), Map.class);

            assertThat(detail).containsKey("sentAt");
            assertThat(detail.get("sentAt")).isInstanceOf(Number.class);
            assertThat(c.fcmData()).containsKey("sentAt");
            assertThat(c.fcmData().get("sentAt")).matches("\\d+");
        }

        /**
         * 판별 케이스: 캡처가 루프 <b>안</b>에 있는지 <b>밖</b>에 있는지를 구분한다.
         *
         * <p>단일 멤버 단정만으로는 구분되지 않는다. 루프 밖 캡처에서도 각 멤버 내부의
         * DB/FCM 일치는 참이기 때문이다. 실측: 캡처를 루프 밖으로 옮겼을 때 전체 232건이 green 이었다.
         *
         * <p>여러 멤버를 넣고 저장 스텁에 지연을 주어 시각이 실제로 진행하게 한 뒤
         * 첫 멤버와 마지막 멤버의 {@code sentAt} 이 서로 다름을 단정한다.
         * 루프 밖 캡처라면 전원이 같은 값을 공유하므로 이 단정이 red 가 된다.
         */
        @Test
        void df4_market_differentMembersReceiveDifferentSentAt() throws Exception {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);

            Member member1 = memberWithToken(1L, "t1");
            Member member2 = memberWithToken(2L, "t2");
            Member member3 = memberWithToken(3L, "t3");
            when(memberRepository.findAll()).thenReturn(List.of(member1, member2, member3));

            List<Notification> saved = new ArrayList<>();
            when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
                // 시각이 실제로 진행하도록 지연을 준다. 지연이 없으면 밀리초 해상도에서
                // 루프 안 캡처여도 값이 같아질 수 있어 판별력이 사라진다.
                Thread.sleep(3);
                saved.add(inv.getArgument(0));
                return inv.getArgument(0);
            });
            when(fcmService.sendExecutionNotification(anyString(), any())).thenReturn(true);

            new MarketNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                    .sendMarketOpenNotification();

            assertThat(saved).hasSize(3);

            ObjectMapper reader = new ObjectMapper();
            long first = ((Number) reader.readValue(saved.get(0).getDetailData(), Map.class)
                    .get("sentAt")).longValue();
            long last = ((Number) reader.readValue(saved.get(2).getDetailData(), Map.class)
                    .get("sentAt")).longValue();

            // 루프 안 캡처: 멤버마다 시각이 진행한다.
            // 루프 밖 캡처였다면 세 값이 모두 같아 이 단정이 red 가 된다.
            assertThat(last).isGreaterThan(first);
        }
    }
}
