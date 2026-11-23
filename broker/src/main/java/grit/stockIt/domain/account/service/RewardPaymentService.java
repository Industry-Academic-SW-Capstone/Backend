package grit.stockIt.domain.account.service;

import grit.stockIt.domain.account.entity.RewardPayment;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.RewardPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

// 부가 서비스가 요청한 미션 보상을 기본 계좌 예수금에 더한다. 정산과 같은 계좌 행 잠금 아래에서 최신 잔고에 더하므로
// 둘이 겹쳐도 한쪽 갱신이 사라지지 않는다. 지급할 때마다 보상 기록을 같은 트랜잭션에 남긴다.
@Slf4j
@Service
@RequiredArgsConstructor
public class RewardPaymentService {

    private final AccountRepository accountRepository;
    private final RewardPaymentRepository rewardPaymentRepository;

    @Transactional
    public void pay(String eventId, Long memberId, long moneyAmount) {
        accountRepository.findDefaultByMemberIdWithLock(memberId)
                .ifPresentOrElse(
                        account -> {
                            BigDecimal amount = BigDecimal.valueOf(moneyAmount);
                            account.increaseCash(amount);
                            rewardPaymentRepository.save(
                                    RewardPayment.of(eventId, account.getAccountId(), memberId, amount));
                            log.info("보상 지급: memberId={} {}원", memberId, moneyAmount);
                        },
                        () -> log.error("보상 지급 실패: 기본 계좌 없음 memberId={}", memberId)
                );
    }
}
