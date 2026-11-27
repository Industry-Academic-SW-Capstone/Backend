package grit.stockIt.global.shard;

// matching.commands 파티션이 어느 거래소 샤드 DB 에 붙는지. 샤드 번호는 0 부터 shardCount()-1 까지다.
public interface ShardRouter {

    int shardOf(int partition);

    int shardCount();
}
