package grit.stockIt.contract;

import java.util.List;
import java.util.Optional;

// 증권사가 부가 서비스에 지금 답을 받아야 하는 드문 조회(대표 칭호 장착·프로필). 칭호는 부가 데이터다.
// 지금은 부가 구현을 메서드로 부르고, 서비스를 나누면(8단계) 구현만 HTTP 클라이언트로 바꾼다.
public interface ExtraQueryClient {

    Optional<TitleView> title(Long titleId);

    boolean ownsTitle(Long memberId, Long titleId);

    List<TitleView> titlesOf(Long memberId);
}
