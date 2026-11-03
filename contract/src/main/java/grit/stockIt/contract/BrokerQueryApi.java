package grit.stockIt.contract;

import java.math.BigDecimal;
import java.util.List;

// BrokerQueryClient 의 HTTP 형식. 증권사가 이 경로로 답하고 부가가 이 경로로 묻는다 — 경로와 본문을 한 곳에 둬 양쪽이 어긋나지 않게 한다.
// 내부망 전용이다. 게이트웨이(Traefik)에 열지 않는다.
public final class BrokerQueryApi {

    public static final String BASE = "/internal/broker";
    public static final String DEFAULT_ACCOUNT_ASSET = "/members/{memberId}/default-account-asset";
    public static final String MEMBERS_HOLDING_STOCK = "/members/holding-stock";
    public static final String MEMBER_IDS = "/members/ids";

    private BrokerQueryApi() {
    }

    // 기본 계좌가 없으면 404 로 답한다.
    public record Asset(BigDecimal amount) {
    }

    public record MemberIds(List<Long> memberIds) {
    }
}
