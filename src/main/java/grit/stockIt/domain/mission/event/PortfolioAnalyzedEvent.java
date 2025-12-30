package grit.stockIt.domain.mission.event;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
/**
 * 포트폴리오 분석 완료 이벤트
 */
@Getter
@RequiredArgsConstructor
public class PortfolioAnalyzedEvent {
    private final String email;      // 미션 수행자
    private final Long accountId;    // 분석한 계좌
}