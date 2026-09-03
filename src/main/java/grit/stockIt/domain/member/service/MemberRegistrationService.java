package grit.stockIt.domain.member.service;

import grit.stockIt.domain.account.service.AccountService;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.event.MemberRegisteredEvent;
import grit.stockIt.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 가입 경로(로컬·카카오)가 공유하는 회원 등록 3단계.
 *
 * 트랜잭션을 스스로 열지 않는다(클래스·메서드 모두 트랜잭션 선언 없음).
 * 호출자의 트랜잭션에 참여해야 미션 초기화 실패가 가입 전체를 롤백시키는 현재 동작이 유지된다.
 */
@Service
@RequiredArgsConstructor
public class MemberRegistrationService {

    private final MemberRepository memberRepository;
    private final AccountService accountService;
    private final ApplicationEventPublisher eventPublisher;

    public Member register(Member member) {
        Member savedMember = memberRepository.save(member);

        accountService.createDefaultAccountForMember(savedMember);

        //  미션 시스템 초기화 (동기 이벤트 — 리스너 예외는 그대로 전파되어 가입 전체 롤백)
        eventPublisher.publishEvent(new MemberRegisteredEvent(savedMember));

        return savedMember;
    }
}
