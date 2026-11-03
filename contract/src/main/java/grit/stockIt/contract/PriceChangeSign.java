package grit.stockIt.contract;

// 등락 부호(KIS 코드). 시세가 실시간 시세로, 증권사가 종목 상세·순위로 함께 내보낸다.
public enum PriceChangeSign {
    UPPER_LIMIT("1", "상한가"),
    RISE("2", "상승"),
    STEADY("3", "보합"),
    LOWER_LIMIT("4", "하한가"),
    FALL("5", "하락");

    private final String code;
    private final String description;

    PriceChangeSign(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    // KIS API 코드로부터 변환. 모르는 코드는 보합으로 본다.
    public static PriceChangeSign fromCode(String code) {
        if (code == null) return STEADY;

        for (PriceChangeSign sign : values()) {
            if (sign.code.equals(code)) {
                return sign;
            }
        }
        return STEADY;
    }
}
