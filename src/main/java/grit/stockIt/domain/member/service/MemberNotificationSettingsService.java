package grit.stockIt.domain.member.service;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MemberNotificationSettingsService {

    private final MemberRepository memberRepository;

    // FCM 토큰 등록/업데이트
    @Transactional
    public void updateFcmToken(String email, String fcmToken) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        member.updateFcmToken(fcmToken);
        memberRepository.save(member);
    }

    // FCM 토큰 삭제
    @Transactional
    public void removeFcmToken(String email) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        member.removeFcmToken();
        memberRepository.save(member);
    }

    // 체결 알림 설정 변경
    @Transactional
    public void updateExecutionNotificationSettings(String email, boolean enabled) {
        Member member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("회원을 찾을 수 없습니다."));

        if (enabled) {
            member.enableExecutionNotification();
        } else {
            member.disableExecutionNotification();
        }
        memberRepository.save(member);
    }
}
