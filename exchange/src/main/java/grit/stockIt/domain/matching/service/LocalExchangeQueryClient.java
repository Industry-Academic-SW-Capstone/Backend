package grit.stockIt.domain.matching.service;

import grit.stockIt.contract.ExchangeQueryClient;
import grit.stockIt.domain.matching.entity.ExchangeOrderStatus;
import grit.stockIt.domain.matching.repository.ExchangeOrderRepository;
import grit.stockIt.global.shard.ShardContext;
import grit.stockIt.global.shard.ShardRouter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

// 거래소가 시세의 드문 조회에 답한다. 지금은 같은 프로세스의 메서드 호출이고, 서비스를 나누면 이 내용이 HTTP 엔드포인트가 된다.
// 트랜잭션은 커넥션을 받을 샤드를 정한 뒤에 열어야 해서 애노테이션이 아니라 샤드마다 연다.
@Service
public class LocalExchangeQueryClient implements ExchangeQueryClient {

    private final ExchangeOrderRepository exchangeOrderRepository;
    private final ShardRouter shardRouter;
    private final TransactionTemplate readOnlyTransaction;

    public LocalExchangeQueryClient(ExchangeOrderRepository exchangeOrderRepository, ShardRouter shardRouter,
                                    PlatformTransactionManager transactionManager) {
        this.exchangeOrderRepository = exchangeOrderRepository;
        this.shardRouter = shardRouter;
        this.readOnlyTransaction = new TransactionTemplate(transactionManager);
        this.readOnlyTransaction.setReadOnly(true);
    }

    @Override
    public List<OpenOrder> openOrders() {
        List<OpenOrder> orders = new ArrayList<>();
        for (int shard = 0; shard < shardRouter.shardCount(); shard++) {
            orders.addAll(ShardContext.call(shard, () -> readOnlyTransaction.execute(
                    status -> exchangeOrderRepository.findOrdersByStatus(ExchangeOrderStatus.OPEN))));
        }
        return orders;
    }
}
