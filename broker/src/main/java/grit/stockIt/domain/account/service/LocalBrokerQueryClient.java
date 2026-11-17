package grit.stockIt.domain.account.service;

import grit.stockIt.contract.BrokerQueryClient;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.account.repository.AccountStockRepository;
import grit.stockIt.domain.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// 증권사가 부가 서비스의 드문 조회에 답한다. 지금은 같은 프로세스의 메서드 호출이고, 서비스를 나누면 이 내용이 HTTP 엔드포인트가 된다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LocalBrokerQueryClient implements BrokerQueryClient {

    private final AccountRepository accountRepository;
    private final AccountStockRepository accountStockRepository;
    private final MemberRepository memberRepository;

    @Override
    public Optional<BigDecimal> defaultAccountAsset(Long memberId) {
        return accountRepository.findByMember_MemberIdAndIsDefaultTrue(memberId)
                .map(account -> account.getCash().add(accountStockRepository.findAllByAccount(account).stream()
                        .map(stock -> stock.getAveragePrice().multiply(BigDecimal.valueOf(stock.getQuantity())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
    }

    @Override
    public Set<Long> membersHoldingStock(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(accountStockRepository.findMemberIdsHoldingStock(memberIds));
    }

    @Override
    public List<Long> allMemberIds() {
        return memberRepository.findAllMemberIds();
    }
}
