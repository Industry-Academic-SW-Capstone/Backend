package grit.stockIt.contract;

// ExtraQueryClient 의 HTTP 형식. 부가가 답하고 증권사가 묻는다. 내부망 전용이다.
public final class ExtraQueryApi {

    public static final String BASE = "/internal/extra";
    // 칭호가 없으면 404 로 답한다.
    public static final String TITLE = "/titles/{titleId}";
    public static final String TITLE_OWNED = "/members/{memberId}/titles/{titleId}/owned";
    public static final String MEMBER_TITLES = "/members/{memberId}/titles";

    private ExtraQueryApi() {
    }

    public record Owned(boolean owned) {
    }
}
