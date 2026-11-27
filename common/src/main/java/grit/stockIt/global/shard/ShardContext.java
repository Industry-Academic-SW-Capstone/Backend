package grit.stockIt.global.shard;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.OptionalInt;
import java.util.function.Supplier;

// 이 스레드가 지금 쓰는 거래소 샤드. 트랜잭션은 열릴 때 커넥션을 받으므로 샤드는 트랜잭션을 열기 전에 정한다.
public final class ShardContext {

    private static final ThreadLocal<Integer> CURRENT = new ThreadLocal<>();

    private ShardContext() {
    }

    public static <T> T call(int shard, Supplier<T> action) {
        if (shard < 0) {
            throw new IllegalArgumentException("샤드 번호는 0 이상이어야 합니다: " + shard);
        }
        Integer previous = CURRENT.get();
        // 샤드를 정하지 않고 연 트랜잭션은 샤드 0 의 커넥션을 쥐고 있다(설정 없음, 또는 기동 중).
        int effective = previous == null ? 0 : previous;
        if (TransactionSynchronizationManager.isActualTransactionActive() && effective != shard) {
            throw new IllegalStateException("트랜잭션 안에서 샤드를 바꿀 수 없습니다: " + effective + " → " + shard);
        }
        CURRENT.set(shard);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    public static void run(int shard, Runnable action) {
        call(shard, () -> {
            action.run();
            return null;
        });
    }

    public static OptionalInt current() {
        Integer shard = CURRENT.get();
        return shard == null ? OptionalInt.empty() : OptionalInt.of(shard);
    }
}
