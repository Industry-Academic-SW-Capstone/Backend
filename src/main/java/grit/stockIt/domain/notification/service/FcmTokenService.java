package grit.stockIt.domain.notification.service;

import grit.stockIt.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FcmTokenService {

    private final MemberRepository memberRepository;

    @Transactional
    public void handleInvalidToken(String fcmToken) {
        memberRepository.findByFcmToken(fcmToken)
                .ifPresent(member -> {
                    member.removeFcmToken();
                    log.info("무효한 FCM 토큰 삭제 완료: memberId={}, token={}", member.getMemberId(), fcmToken);
                });
    }
}

