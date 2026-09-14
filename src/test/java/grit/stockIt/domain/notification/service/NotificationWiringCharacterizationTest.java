package grit.stockIt.domain.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * notification 도메인 4개 서비스의 배선(wiring)을 고정하는 불변 특성화 파일 #1.
 *
 * <p><b>이 파일은 태그 {@code notification-charac-base} 이후 절대 수정하지 않는다.</b>
 * 수정이 필요하다고 느껴지면 그것은 이 파일이 잘못 분류됐다는 신호이며 계획 재수립이 필요하다.
 * 완료 기준: {@code git log --oneline notification-charac-base..HEAD -- <이 파일>} 이 0 커밋.
 *
 * <h2>불변 파일 내용 규칙</h2>
 * <ul>
 *   <li><b>R1</b> — {@code %d}/{@code %,d} 포맷 출력을 포함하는 문자열은 어떤 형태로도 이 파일에
 *       넣지 않는다. 로케일 고정 수정(C5)이 그 기대값을 무효화하기 때문이다. 이 파일은 값이 아니라
 *       <b>호출 여부와 횟수</b>만 관측하므로 규칙을 자연히 만족한다.</li>
 *   <li><b>R2</b> — 두 경로(DB/FCM) 값 일치 단정과 Market {@code sentAt} 값 단정은
 *       결함 동결 파일이 단독 소유한다. 이 파일에는 없다.</li>
 *   <li><b>R3</b> — 프로덕션 타입 + JUnit/Mockito/AssertJ 에만 의존한다. 프로젝트 내부 테스트
 *       헬퍼·베이스 클래스·공유 픽스처를 참조하지 않는다. 헬퍼를 통한 간접 무력화를 차단한다.</li>
 *   <li><b>R4 (하네스 규칙)</b> — {@code ObjectMapper} 는 순수 목이 아니라
 *       {@code spy(new ObjectMapper())} 를 쓴다. 순수 {@code mock(ObjectMapper.class)} 는
 *       {@code writeValueAsString} 이 {@code null} 을 반환하므로 캡처된 {@code getDetailData()} 가
 *       {@code null} 이 되고 직렬화 관련 단정이 null 대 null 로 <b>조용히 통과</b>한다.
 *       공허 통과는 green 이라 C1 게이트가 잡지 못하고 그대로 영구 동결된다.
 *       파생 규약: 예외 주입은 전역 스텁이 아니라 <b>특정 호출에만</b> 건다.</li>
 * </ul>
 *
 * <h2>스프링 컨텍스트를 쓰지 않는 이유</h2>
 * 잡아야 할 배선 7가지 전부가 public 진입점 + Mockito 목으로 결정론적으로 관측 가능하다.
 * 프로덕션의 {@code @Async} 는 컨텍스트가 없으면 비활성이므로 직접 호출이 동기적이 된다.
 * 컨텍스트를 띄우면 오히려 이벤트 리스너 관측이 비결정적이 되고 Testcontainers 기동 비용이 붙는다.
 * 애노테이션 유실은 배선 7의 리플렉션 단정이 잡는다.
 */
class NotificationWiringCharacterizationTest {

    // ---------------------------------------------------------------------
    // 멤버 목 생성 헬퍼. W5-M/W6-M 픽스처는 각 테스트가 지역에서 직접 구성한다.
    // ---------------------------------------------------------------------

    private static Member memberWithToken(long id, String token) {
        Member member = mock(Member.class);
        when(member.getMemberId()).thenReturn(id);
        when(member.hasFcmToken()).thenReturn(true);
        when(member.getFcmToken()).thenReturn(token);
        return member;
    }

    private static Member memberWithoutToken(long id) {
        Member member = mock(Member.class);
        when(member.getMemberId()).thenReturn(id);
        when(member.hasFcmToken()).thenReturn(false);
        return member;
    }

    private static ExecutionFilledEvent executionEvent() {
        return new ExecutionFilledEvent(
                1L, 2L, 3L, 100L, 4L, "테스트대회",
                "005930", "삼성전자",
                new BigDecimal("70000"), 10, "BUY");
    }

    private static MissionCompletedEvent missionEvent() {
        return new MissionCompletedEvent(
                100L, 200L, "첫 거래", MissionTrack.DAILY, 300L, 0L, null);
    }

    // =====================================================================
    // 배선 1 — fcmService 가 null 인 경로
    //   FcmService 는 @ConditionalOnBean(FirebaseMessaging.class) 이고 4개 서비스가
    //   @Autowired(required = false) 로 받으므로 런타임에 null 일 수 있다.
    //   4개 서비스 전부 null 가드를 보유한다. 카운터 값 단정은 하지 않는다.
    // =====================================================================

    @Nested
    @DisplayName("배선 1: fcmService 가 null 이어도 DB 저장은 진행되고 예외가 나지 않는다")
    class WiringFcmServiceNull {

        @Test
        void w1_execution_nullFcmService_savesAndDoesNotThrow() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            Member member = memberWithToken(100L, "token-a");
            when(memberRepository.findById(100L)).thenReturn(Optional.of(member));

            ExecutionNotificationService service = new ExecutionNotificationService(
                    null, memberRepository, notificationRepository, objectMapper);

            assertThatCode(() -> service.handleExecutionFilledEvent(executionEvent()))
                    .doesNotThrowAnyException();

            verify(notificationRepository, times(1)).save(any(Notification.class));
        }

        @Test
        void w1_mission_nullFcmService_savesAndDoesNotThrow() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            Member member = memberWithToken(100L, "token-a");
            when(memberRepository.findById(100L)).thenReturn(Optional.of(member));

            MissionNotificationService service = new MissionNotificationService(
                    null, memberRepository, notificationRepository, objectMapper);

            assertThatCode(() -> service.handleMissionCompletedEvent(missionEvent()))
                    .doesNotThrowAnyException();

            verify(notificationRepository, times(1)).save(any(Notification.class));
        }

        @Test
        void w1_market_nullFcmService_savesAndCompletesLoop() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            Member member0 = memberWithToken(1L, "t1");
            when(memberRepository.findAll()).thenReturn(List.of(member0));

            MarketNotificationService service = new MarketNotificationService(
                    null, memberRepository, notificationRepository, objectMapper);

            assertThatCode(service::sendMarketOpenNotification).doesNotThrowAnyException();

            verify(notificationRepository, times(1)).save(any(Notification.class));
        }

        @Test
        void w1_admin_nullFcmService_savesAndDoesNotThrow() {
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());

            AdminNotificationService service = new AdminNotificationService(
                    null, notificationRepository, objectMapper);

            assertThatCode(() -> service.sendBroadcastNotification(
                    List.of(memberWithToken(1L, "t1")), "공지 제목", "공지 본문"))
                    .doesNotThrowAnyException();

            verify(notificationRepository, times(1)).save(any(Notification.class));
        }
    }

    // =====================================================================
    // 배선 2 — hasFcmToken 게이트
    //   Execution/Mission 은 DB 저장 후 FCM 단계에서 걸러진다.
    //   Market 은 루프 최상단에서 continue 하므로 저장 자체가 일어나지 않는다.
    //   Admin 은 저장 후 FCM 단계에서 걸러진다.
    //   이 비대칭이 의도임을 반대 단정으로 함께 고정한다.
    // =====================================================================

    @Nested
    @DisplayName("배선 2: FCM 토큰이 없으면 서비스마다 다른 지점에서 걸러진다")
    class WiringHasFcmTokenGate {

        @Test
        void w2_execution_noToken_savesButSkipsFcm() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);
            Member targetMember = memberWithoutToken(100L);
            when(memberRepository.findById(100L)).thenReturn(Optional.of(targetMember));

            new ExecutionNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                    .handleExecutionFilledEvent(executionEvent());

            verify(notificationRepository, times(1)).save(any(Notification.class));
            verify(fcmService, never()).sendExecutionNotification(anyString(), any());
        }

        @Test
        void w2_mission_noToken_savesButSkipsFcm() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);
            Member targetMember = memberWithoutToken(100L);
            when(memberRepository.findById(100L)).thenReturn(Optional.of(targetMember));

            new MissionNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                    .handleMissionCompletedEvent(missionEvent());

            verify(notificationRepository, times(1)).save(any(Notification.class));
            verify(fcmService, never()).sendExecutionNotification(anyString(), any());
        }

        @Test
        void w2_market_noToken_skipsSaveEntirely() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);
            Member member0 = memberWithoutToken(1L);
            when(memberRepository.findAll()).thenReturn(List.of(member0));

            new MarketNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                    .sendMarketOpenNotification();

            verify(notificationRepository, never()).save(any(Notification.class));
            verify(fcmService, never()).sendExecutionNotification(anyString(), any());
        }

        @Test
        void w2_admin_noToken_savesButSkipsFcm() {
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);

            new AdminNotificationService(fcmService, notificationRepository, objectMapper)
                    .sendBroadcastNotification(List.of(memberWithoutToken(1L)), "제목", "본문");

            verify(notificationRepository, times(1)).save(any(Notification.class));
            verify(fcmService, never()).sendExecutionNotification(anyString(), any());
        }
    }

    // =====================================================================
    // 배선 3 — isExecutionNotificationEnabled 게이트 (Execution 전용)
    //   이 게이트는 Execution 에만 있다. 나머지 3개는 같은 조건에서 FCM 을 보낸다.
    //   양성 케이스(enabled=true → 정확히 1회)를 함께 고정해 M8 뮤테이션이 red 가 되게 한다.
    // =====================================================================

    @Nested
    @DisplayName("배선 3: 체결 알림 비활성 게이트는 Execution 에만 있다")
    class WiringExecutionNotificationEnabledGate {

        @Test
        void w3_execution_disabled_skipsFcm() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);
            Member member = memberWithToken(100L, "token-a");
            when(member.isExecutionNotificationEnabled()).thenReturn(false);
            when(memberRepository.findById(100L)).thenReturn(Optional.of(member));

            new ExecutionNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                    .handleExecutionFilledEvent(executionEvent());

            verify(notificationRepository, times(1)).save(any(Notification.class));
            verify(fcmService, never()).sendExecutionNotification(anyString(), any());
        }

        /**
         * 양성 케이스. M8(게이트 조건 반전) 뮤테이션이 이 단정으로 red 가 된다.
         */
        @Test
        void w3_execution_enabled_sendsFcmExactlyOnce() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);
            Member member = memberWithToken(100L, "token-a");
            when(member.isExecutionNotificationEnabled()).thenReturn(true);
            when(memberRepository.findById(100L)).thenReturn(Optional.of(member));

            new ExecutionNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                    .handleExecutionFilledEvent(executionEvent());

            verify(fcmService, times(1)).sendExecutionNotification(anyString(), any());
        }

        /**
         * 게이트 비대칭이 의도임을 못박는 반대 단정.
         * Mission 은 isExecutionNotificationEnabled 를 보지 않는다.
         */
        @Test
        void w3_mission_ignoresExecutionEnabledFlag_stillSendsFcm() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);
            Member member = memberWithToken(100L, "token-a");
            when(member.isExecutionNotificationEnabled()).thenReturn(false);
            when(memberRepository.findById(100L)).thenReturn(Optional.of(member));

            new MissionNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                    .handleMissionCompletedEvent(missionEvent());

            verify(fcmService, times(1)).sendExecutionNotification(anyString(), any());
        }

        @Test
        void w3_market_ignoresExecutionEnabledFlag_stillSendsFcm() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);
            Member member = memberWithToken(1L, "t1");
            when(member.isExecutionNotificationEnabled()).thenReturn(false);
            Member member0 = member;
            when(memberRepository.findAll()).thenReturn(List.of(member0));

            new MarketNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                    .sendMarketOpenNotification();

            verify(fcmService, times(1)).sendExecutionNotification(anyString(), any());
        }

        @Test
        void w3_admin_ignoresExecutionEnabledFlag_stillSendsFcm() {
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);
            Member member = memberWithToken(1L, "t1");
            when(member.isExecutionNotificationEnabled()).thenReturn(false);

            new AdminNotificationService(fcmService, notificationRepository, objectMapper)
                    .sendBroadcastNotification(List.of(member), "제목", "본문");

            verify(fcmService, times(1)).sendExecutionNotification(anyString(), any());
        }
    }

    // =====================================================================
    // 배선 4 — 예외 재던지기 (직접 호출 시 호출자에게 전파)
    //
    //   프로덕션에서는 @Async 라 예외가 발행자에게 도달하지 않고, @EventListener 이므로
    //   롤백 범위는 알림 트랜잭션에 한정된다. 이 단정은 롤백의 증명이 아니라
    //   전파 배선의 고정이다.
    // =====================================================================

    @Nested
    @DisplayName("배선 4: 멤버 조회 실패 시 예외가 호출자에게 전파된다")
    class WiringExceptionRethrow {

        @Test
        void w4_execution_memberNotFound_propagates() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            when(memberRepository.findById(100L)).thenReturn(Optional.empty());

            ExecutionNotificationService service = new ExecutionNotificationService(
                    mock(FcmService.class), memberRepository, notificationRepository, objectMapper);

            assertThatThrownBy(() -> service.handleExecutionFilledEvent(executionEvent()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Member를 찾을 수 없습니다");

            verify(notificationRepository, never()).save(any(Notification.class));
        }

        @Test
        void w4_mission_memberNotFound_propagates() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            when(memberRepository.findById(100L)).thenReturn(Optional.empty());

            MissionNotificationService service = new MissionNotificationService(
                    mock(FcmService.class), memberRepository, notificationRepository, objectMapper);

            assertThatThrownBy(() -> service.handleMissionCompletedEvent(missionEvent()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Member를 찾을 수 없습니다");

            verify(notificationRepository, never()).save(any(Notification.class));
        }

        @Test
        void w4_market_findAllThrows_propagates() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            when(memberRepository.findAll()).thenThrow(new IllegalStateException("DB down"));

            MarketNotificationService service = new MarketNotificationService(
                    mock(FcmService.class), memberRepository, notificationRepository, objectMapper);

            assertThatThrownBy(service::sendMarketOpenNotification)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DB down");
        }
    }

    // =====================================================================
    // 배선 5 — JSON 직렬화 실패 경로
    //
    //   하네스는 R4 의 공유 spy(new ObjectMapper()) 이며 예외는 대상 호출에만 건다.
    //   나머지 호출은 실제 직렬화를 수행한다. 이렇게 해야 "삼키고 계속"의 계속 부분까지 고정된다.
    //
    //   선택 형태가 서비스마다 다르다:
    //     - Execution/Mission: createDetailData 가 이벤트를 받아 이벤트 필드를 맵에 담으므로
    //       argThat 인자 분기가 유효하다.
    //     - Market: createDetailData(NotificationType) 이 멤버 인자를 받지 않고 {type, sentAt}
    //       두 키만 만들므로 어떤 argThat 술어도 "이 멤버의 호출"을 식별할 수 없다.
    //       따라서 호출 순서 기반 스텁을 쓴다.
    //
    //   픽스처 W5-M: 멤버 4명 전원 토큰 보유. 배선 6의 W6-M(토큰 2/무토큰 2)과 다르며
    //   헬퍼를 공유하지 않는다. 3/3 기대가 참인 이유는 오직 전원 토큰 보유이기 때문이다.
    // =====================================================================

    @Nested
    @DisplayName("배선 5: JSON 직렬화 실패는 서비스마다 다르게 처리된다")
    class WiringJsonFailure {

        @Test
        void w5_execution_jsonFailure_propagates() throws Exception {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            Member targetMember = memberWithToken(100L, "token-a");
            when(memberRepository.findById(100L)).thenReturn(Optional.of(targetMember));

            // 인자 분기: executionId 키를 담은 맵으로 넘어오는 호출만 던진다
            doThrow(new JsonProcessingException("boom") { })
                    .when(objectMapper)
                    .writeValueAsString(argThat((Object arg) ->
                            arg instanceof Map<?, ?> m && m.containsKey("executionId")));

            ExecutionNotificationService service = new ExecutionNotificationService(
                    mock(FcmService.class), memberRepository, notificationRepository, objectMapper);

            assertThatThrownBy(() -> service.handleExecutionFilledEvent(executionEvent()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JSON 변환");
        }

        @Test
        void w5_mission_jsonFailure_propagates() throws Exception {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            Member targetMember = memberWithToken(100L, "token-a");
            when(memberRepository.findById(100L)).thenReturn(Optional.of(targetMember));

            doThrow(new JsonProcessingException("boom") { })
                    .when(objectMapper)
                    .writeValueAsString(argThat((Object arg) ->
                            arg instanceof Map<?, ?> m && m.containsKey("missionId")));

            MissionNotificationService service = new MissionNotificationService(
                    mock(FcmService.class), memberRepository, notificationRepository, objectMapper);

            assertThatThrownBy(() -> service.handleMissionCompletedEvent(missionEvent()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JSON 변환");
        }

        @Test
        void w5_admin_jsonFailure_savesEmptyObjectAndContinues() throws Exception {
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);

            doThrow(new JsonProcessingException("boom") { })
                    .when(objectMapper)
                    .writeValueAsString(any(Object.class));

            List<Notification> saved = new ArrayList<>();
            when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
                saved.add(inv.getArgument(0));
                return inv.getArgument(0);
            });

            new AdminNotificationService(fcmService, notificationRepository, objectMapper)
                    .sendBroadcastNotification(List.of(memberWithToken(1L, "t1")), "제목", "본문");

            assertThat(saved).hasSize(1);
            assertThat(saved.get(0).getDetailData()).isEqualTo("{}");
        }

        /**
         * 픽스처 W5-M (fourTokenHolders): 멤버 4명 전원 토큰 보유.
         * 첫 호출만 실패하고 나머지 3명은 완주한다. 3/3 기대가 참인 이유는 전원 토큰 보유이기 때문이다.
         * 배선 6의 W6-M(토큰 2/무토큰 2)을 여기 가져다 쓰면 통과 멤버가 1명뿐이라 red 가 난다.
         */
        @Test
        void w5_market_oneOfFourTokenHoldersFails_swallowsAndContinues() throws Exception {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);
            when(fcmService.sendExecutionNotification(anyString(), any())).thenReturn(true);

            // W5-M: 4명 전원 토큰 보유
            Member member0 = memberWithToken(1L, "t1");
            Member member1 = memberWithToken(2L, "t2");
            Member member2 = memberWithToken(3L, "t3");
            Member member3 = memberWithToken(4L, "t4");
            when(memberRepository.findAll()).thenReturn(List.of(member0, member1, member2, member3));

            // Market 은 인자 분기 불가 → 호출 순서 기반 스텁 (AtomicInteger 카운터 형태)
            AtomicInteger callCount = new AtomicInteger(0);
            doAnswer(invocation -> {
                if (callCount.getAndIncrement() == 0) {
                    throw new JsonProcessingException("boom") { };
                }
                return invocation.callRealMethod();
            }).when(objectMapper).writeValueAsString(any(Object.class));

            MarketNotificationService service = new MarketNotificationService(
                    fcmService, memberRepository, notificationRepository, objectMapper);

            assertThatCode(service::sendMarketOpenNotification).doesNotThrowAnyException();

            verify(notificationRepository, times(3)).save(any(Notification.class));
            verify(fcmService, times(3)).sendExecutionNotification(anyString(), any());
        }

        /**
         * 같은 W5-M 픽스처(4명 전원 토큰)에서 전 호출이 실패하는 변형.
         * never() 단정은 이 케이스에서 얻는다.
         * createDetailData 가 save 보다 먼저 실행되므로 저장은 한 번도 도달하지 않는다.
         */
        @Test
        void w5_market_allFourTokenHoldersFail_neverSavesAndReturnsNormally() throws Exception {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);

            Member member0 = memberWithToken(1L, "t1");
            Member member1 = memberWithToken(2L, "t2");
            Member member2 = memberWithToken(3L, "t3");
            Member member3 = memberWithToken(4L, "t4");
            when(memberRepository.findAll()).thenReturn(List.of(member0, member1, member2, member3));

            doThrow(new JsonProcessingException("boom") { })
                    .when(objectMapper)
                    .writeValueAsString(any(Object.class));

            MarketNotificationService service = new MarketNotificationService(
                    fcmService, memberRepository, notificationRepository, objectMapper);

            assertThatCode(service::sendMarketOpenNotification).doesNotThrowAnyException();

            verify(notificationRepository, never()).save(any(Notification.class));
            verify(fcmService, never()).sendExecutionNotification(anyString(), any());
        }
    }

    // =====================================================================
    // 배선 6 — Market 루프 진행 (픽스처 W6-M)
    //
    //   픽스처 W6-M: 멤버 4명, 토큰 보유 2 / 미보유 2. 이 케이스에는 예외 주입이 없다.
    //   W5-M(전원 토큰 4/4, save 3·FCM 3)과 서로 다른 픽스처이며 헬퍼를 공유하지 않는다.
    //
    //   successCount/failCount/skippedCount 값 단정은 하지 않는다 — 지역 변수 + 로그뿐이라
    //   접근자 추가 없이는 관측 불가하고, 접근자 추가는 AC-4 를 깨는 유일한 현실 경로다.
    //   Admin 은 BroadcastResult 를 반환하므로 직접 단정한다.
    // =====================================================================

    @Nested
    @DisplayName("배선 6: Market 루프는 토큰 보유자만 처리한다")
    class WiringMarketLoop {

        @Test
        void w6_market_twoTokenHoldersTwoWithout_savesTwoAndSendsTwo() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);

            // W6-M: 토큰 2 / 무토큰 2
            Member member0 = memberWithToken(1L, "t1");
            Member member1 = memberWithoutToken(2L);
            Member member2 = memberWithToken(3L, "t3");
            Member member3 = memberWithoutToken(4L);
            when(memberRepository.findAll()).thenReturn(List.of(member0, member1, member2, member3));

            // 토큰 보유 2명 중 성공 1 · 실패 1
            when(fcmService.sendExecutionNotification(anyString(), any()))
                    .thenReturn(true)
                    .thenReturn(false);

            new MarketNotificationService(fcmService, memberRepository, notificationRepository, objectMapper)
                    .sendMarketOpenNotification();

            verify(notificationRepository, times(2)).save(any(Notification.class));
            verify(fcmService, times(2)).sendExecutionNotification(anyString(), any());
        }

        @Test
        void w6_admin_returnsBroadcastResultCounts() {
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            FcmService fcmService = mock(FcmService.class);
            when(fcmService.sendExecutionNotification(anyString(), any()))
                    .thenReturn(true)
                    .thenReturn(false);

            AdminNotificationService.BroadcastResult result =
                    new AdminNotificationService(fcmService, notificationRepository, objectMapper)
                            .sendBroadcastNotification(List.of(
                                    memberWithToken(1L, "t1"),
                                    memberWithToken(2L, "t2"),
                                    memberWithoutToken(3L)), "제목", "본문")
                            .join();

            // Admin 은 토큰 없는 멤버에게도 DB 저장은 한다
            assertThat(result.savedCount()).isEqualTo(3);
            assertThat(result.successCount()).isEqualTo(1);
            assertThat(result.failCount()).isEqualTo(1);
        }
    }

    // =====================================================================
    // 배선 7 — 애노테이션 보존
    //
    //   C3 가 4개 서비스를 전부 수정하므로 @Async/@Transactional/@EventListener 유실을
    //   다른 어떤 단정도 잡지 못한다. 목 테스트는 스프링 프록시를 타지 않기 때문이다.
    //   리플렉션 단정은 스프링 컨텍스트가 필요 없으므로 목 전용 결정과 정합한다.
    // =====================================================================

    @Nested
    @DisplayName("배선 7: 진입점 애노테이션이 보존된다")
    class WiringAnnotationPreservation {

        private Method method(Class<?> type, String name, Class<?>... params) throws Exception {
            return type.getDeclaredMethod(name, params);
        }

        @Test
        void w7_execution_entryPointKeepsAsyncEventListenerTransactional() throws Exception {
            Method m = method(ExecutionNotificationService.class,
                    "handleExecutionFilledEvent", ExecutionFilledEvent.class);

            assertThat(m.isAnnotationPresent(Async.class)).isTrue();
            assertThat(m.isAnnotationPresent(EventListener.class)).isTrue();
            assertThat(m.isAnnotationPresent(Transactional.class)).isTrue();
        }

        @Test
        void w7_mission_entryPointKeepsAsyncEventListenerTransactional() throws Exception {
            Method m = method(MissionNotificationService.class,
                    "handleMissionCompletedEvent", MissionCompletedEvent.class);

            assertThat(m.isAnnotationPresent(Async.class)).isTrue();
            assertThat(m.isAnnotationPresent(EventListener.class)).isTrue();
            assertThat(m.isAnnotationPresent(Transactional.class)).isTrue();
        }

        @Test
        void w7_market_bothEntryPointsKeepAsyncTransactional() throws Exception {
            Method open = method(MarketNotificationService.class, "sendMarketOpenNotification");
            Method close = method(MarketNotificationService.class, "sendMarketCloseReminderNotification");

            assertThat(open.isAnnotationPresent(Async.class)).isTrue();
            assertThat(open.isAnnotationPresent(Transactional.class)).isTrue();
            assertThat(close.isAnnotationPresent(Async.class)).isTrue();
            assertThat(close.isAnnotationPresent(Transactional.class)).isTrue();
        }

        @Test
        void w7_admin_entryPointKeepsAsyncTransactional() throws Exception {
            Method m = method(AdminNotificationService.class,
                    "sendBroadcastNotification", List.class, String.class, String.class);

            assertThat(m.isAnnotationPresent(Async.class)).isTrue();
            assertThat(m.isAnnotationPresent(Transactional.class)).isTrue();
        }
    }

    // =====================================================================
    // 각 서비스는 자기 NotificationType 만 사용한다
    //   NotificationType 참조는 notification 도메인 내 5곳뿐이고 전부 서비스 내부다.
    // =====================================================================

    @Nested
    @DisplayName("각 서비스는 자기 NotificationType 만 사용한다")
    class WiringNotificationTypeBinding {

        @Test
        void w8_eachServiceUsesItsOwnType() {
            MemberRepository memberRepository = mock(MemberRepository.class);
            NotificationRepository notificationRepository = mock(NotificationRepository.class);
            ObjectMapper objectMapper = spy(new ObjectMapper());
            Member targetMember = memberWithToken(100L, "token-a");
            when(memberRepository.findById(100L)).thenReturn(Optional.of(targetMember));

            List<Notification> saved = new ArrayList<>();
            when(notificationRepository.save(any(Notification.class))).thenAnswer(inv -> {
                saved.add(inv.getArgument(0));
                return inv.getArgument(0);
            });

            new ExecutionNotificationService(mock(FcmService.class), memberRepository,
                    notificationRepository, objectMapper)
                    .handleExecutionFilledEvent(executionEvent());
            new MissionNotificationService(mock(FcmService.class), memberRepository,
                    notificationRepository, objectMapper)
                    .handleMissionCompletedEvent(missionEvent());

            assertThat(saved).hasSize(2);
            assertThat(saved.get(0).getType()).isEqualTo(NotificationType.EXECUTION);
            assertThat(saved.get(1).getType()).isEqualTo(NotificationType.MISSION_COMPLETED);
        }
    }
}
