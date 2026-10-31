package grit.stockIt.contract;

public enum ExecutionReportType {
    // 거래소가 주문을 오더북에 넣었다.
    ACCEPTED,
    // 거래소가 주문을 받지 않았다. 증권사는 주문을 끝내고 홀딩을 푼다.
    REJECTED,
    FILL,
    CANCELED,
    EXPIRED
}
