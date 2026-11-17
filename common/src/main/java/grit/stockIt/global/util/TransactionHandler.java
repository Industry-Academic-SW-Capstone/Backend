package grit.stockIt.global.util;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 트랜잭션 동기화를 위한 유틸리티 클래스
public class TransactionHandler {

    // 현재 트랜잭션이 성공적으로 커밋된 직후에 runnable을 실행
    public static void afterCommit(Runnable runnable) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        runnable.run();
                    }
                }
            );
        } else {
            // 트랜잭션이 없는 상황이라면 그냥 즉시 실행
            runnable.run();
        }
    }
}

