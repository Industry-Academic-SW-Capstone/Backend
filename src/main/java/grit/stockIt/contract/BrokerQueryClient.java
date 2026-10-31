package grit.stockIt.contract;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// 부가 서비스가 증권사에 지금 답을 받아야 하는 드문 조회. 부가는 이 인터페이스만 알고, 지금은 증권사 구현을 메서드로 부르며
// 서비스를 나누면(8단계) 구현만 HTTP 클라이언트로 바꾼다. 원격 호출이 될 것이므로 회원마다 묻지 않게 묶음으로 묻는다.
public interface BrokerQueryClient {

    // 기본 계좌의 현금 + 보유 주식 원금(평단가 × 수량). 기본 계좌가 없으면 빈 값.
    Optional<BigDecimal> defaultAccountAsset(Long memberId);

    // 주어진 회원 중 어느 계좌에든 주식을 1주 이상 가진 회원.
    Set<Long> membersHoldingStock(Collection<Long> memberIds);

    List<Long> allMemberIds();
}
