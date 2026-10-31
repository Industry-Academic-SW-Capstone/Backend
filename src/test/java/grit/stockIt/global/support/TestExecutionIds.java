package grit.stockIt.global.support;

import java.util.concurrent.atomic.AtomicLong;

// 거래소가 붙이는 체결 번호를 테스트가 직접 만들 때 쓴다. 테스트끼리 DB 를 함께 쓰므로 겹치지 않게 큰 수에서 시작한다.
public final class TestExecutionIds {

    private static final AtomicLong NEXT = new AtomicLong(System.currentTimeMillis() * 1000);

    private TestExecutionIds() {
    }

    public static long next() {
        return NEXT.incrementAndGet();
    }
}
