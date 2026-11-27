package grit.stockIt.global.shard;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("스레드의 지금 샤드")
class ShardContextTest {

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("안에서 정한 샤드는 끝나면 바깥 값으로 되돌아간다")
    void restoresPrevious() {
        assertThat(ShardContext.current()).isEmpty();

        ShardContext.run(1, () -> {
            assertThat(ShardContext.current()).isEqualTo(OptionalInt.of(1));
            int inner = ShardContext.call(2, () -> ShardContext.current().getAsInt());
            assertThat(inner).isEqualTo(2);
            assertThat(ShardContext.current()).isEqualTo(OptionalInt.of(1));
        });

        assertThat(ShardContext.current()).isEmpty();
    }

    @Test
    @DisplayName("예외로 끝나도 되돌아간다")
    void restoresPreviousOnException() {
        assertThatThrownBy(() -> ShardContext.run(1, () -> {
            throw new IllegalArgumentException("boom");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(ShardContext.current()).isEmpty();
    }

    @Test
    @DisplayName("트랜잭션 안에서 다른 샤드로 바꾸면 예외")
    void switchInsideTransaction_throws() {
        ShardContext.run(0, () -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> ShardContext.run(1, () -> { }))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(ShardContext.current()).isEqualTo(OptionalInt.of(0));
        });
    }

    @Test
    @DisplayName("트랜잭션 안에서 같은 샤드를 다시 정하는 것은 된다")
    void sameShardInsideTransaction_ok() {
        ShardContext.run(1, () -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThat(ShardContext.call(1, () -> "ok")).isEqualTo("ok");
        });
    }

    @Test
    @DisplayName("샤드 없이 연 트랜잭션은 샤드 0 의 커넥션이라 0 은 되고 다른 샤드는 예외")
    void transactionWithoutShard_onlyShardZero() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> ShardContext.run(1, () -> { })).isInstanceOf(IllegalStateException.class);
        assertThat(ShardContext.call(0, () -> "ok")).isEqualTo("ok");
        assertThat(ShardContext.current()).isEmpty();
    }

    @Test
    @DisplayName("음수 샤드는 받지 않는다")
    void negativeShard_throws() {
        assertThatThrownBy(() -> ShardContext.run(-1, () -> { })).isInstanceOf(IllegalArgumentException.class);
    }
}
