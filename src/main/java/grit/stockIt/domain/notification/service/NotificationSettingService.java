package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.notification.entity.NotificationSetting;
import grit.stockIt.domain.notification.repository.NotificationSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 회원의 푸시 수신 설정(FCM 토큰, 체결 알림 수신 여부)을 읽고 바꾼다. 행이 없으면 기본값으로 만든다.
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSettingService {

    private final NotificationSettingRepository notificationSettingRepository;

    // 행이 없는 회원은 저장하지 않은 기본값을 돌려준다(토큰 없음, 체결 알림 켬).
    @Transactional(readOnly = true)
    public NotificationSetting settingOf(Long memberId) {
        return notificationSettingRepository.findById(memberId).orElseGet(() -> NotificationSetting.defaultFor(memberId));
    }

    @Transactional
    public void updateFcmToken(Long memberId, String fcmToken) {
        getOrCreate(memberId).updateFcmToken(fcmToken);
    }

    @Transactional
    public void removeFcmToken(Long memberId) {
        notificationSettingRepository.findById(memberId).ifPresent(NotificationSetting::removeFcmToken);
    }

    @Transactional
    public void updateExecutionNotification(Long memberId, boolean enabled) {
        getOrCreate(memberId).updateExecutionNotification(enabled);
    }

    // 관리자 전체 발송의 수신자. 회원마다 수신 설정을 붙이고, 설정이 없는 회원은 토큰 없음으로 본다(알림 내역만 남는다).
    @Transactional(readOnly = true)
    public List<NotificationSetting> recipientsOf(List<Long> memberIds) {
        Map<Long, NotificationSetting> settings = notificationSettingRepository.findAllById(memberIds).stream()
                .collect(Collectors.toMap(NotificationSetting::getMemberId, setting -> setting));
        return memberIds.stream()
                .map(memberId -> settings.getOrDefault(memberId, NotificationSetting.defaultFor(memberId)))
                .toList();
    }

    // FCM 이 무효라고 알려 온 토큰을 지운다.
    @Transactional
    public void handleInvalidToken(String fcmToken) {
        notificationSettingRepository.findAllByFcmToken(fcmToken).forEach(setting -> {
            setting.removeFcmToken();
            log.info("무효한 FCM 토큰 삭제 완료: memberId={}, token={}", setting.getMemberId(), fcmToken);
        });
    }

    private NotificationSetting getOrCreate(Long memberId) {
        return notificationSettingRepository.findById(memberId)
                .orElseGet(() -> notificationSettingRepository.save(NotificationSetting.defaultFor(memberId)));
    }
}
