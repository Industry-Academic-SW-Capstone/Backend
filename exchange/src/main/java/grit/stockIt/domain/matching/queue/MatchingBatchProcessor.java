package grit.stockIt.domain.matching.queue;

import grit.stockIt.domain.matching.queue.MatchingCommandHandler.CommandResult;
import grit.stockIt.domain.matching.repository.ConsumerWatermarkWriter;
import grit.stockIt.domain.matching.service.FillIdGenerator;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

// poll 한 번의 명령을 같은 파티션 구간마다 batch-max 개씩 끊어 묶음 하나를 트랜잭션 하나로 처리해 커밋 수를 줄인다. 묶음이 실패하면 한 건씩 각자 트랜잭션으로 다시 처리한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingBatchProcessor {

    private static final LogAccessor DESERIALIZATION_LOG = new LogAccessor(MatchingBatchProcessor.class);

    private final MatchingCommandHandler handler;
    private final MatchingQueueMetrics metrics;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final FillIdGenerator fillIdGenerator;
    private final ConsumerWatermarkWriter consumerWatermarkWriter;

    @Value("${matching.queue.batch-max:20}")
    private int batchMax;

    public void process(List<ConsumerRecord<String, MatchingCommand>> records) {
        for (List<ConsumerRecord<String, MatchingCommand>> run : partitionRuns(records)) {
            processRun(run);
        }
    }

    // 에러 핸들러가 실패 레코드보다 poll 순서로 앞선 offset 을 모두 커밋하므로, 섞여 와도 이어진 같은 파티션끼리만 묶는다.
    private static List<List<ConsumerRecord<String, MatchingCommand>>> partitionRuns(
            List<ConsumerRecord<String, MatchingCommand>> records) {
        List<List<ConsumerRecord<String, MatchingCommand>>> runs = new ArrayList<>();
        List<ConsumerRecord<String, MatchingCommand>> current = null;
        for (ConsumerRecord<String, MatchingCommand> record : records) {
            if (current == null || !samePartition(current.get(0), record)) {
                current = new ArrayList<>();
                runs.add(current);
            }
            current.add(record);
        }
        return runs;
    }

    private static boolean samePartition(ConsumerRecord<?, ?> a, ConsumerRecord<?, ?> b) {
        return a.partition() == b.partition() && a.topic().equals(b.topic());
    }

    private void processRun(List<ConsumerRecord<String, MatchingCommand>> run) {
        int unreadableAt = firstUnreadable(run);
        if (unreadableAt > 0) {
            processInChunks(run.subList(0, unreadableAt));
        }
        if (unreadableAt < run.size()) {
            throw unreadable(run.get(unreadableAt));
        }
    }

    // 앞 묶음은 이미 커밋돼 있어 뒤 묶음의 실패 레코드로 에러 핸들러에 넘겨도 offset 이 앞서 커밋되지 않는다.
    private void processInChunks(List<ConsumerRecord<String, MatchingCommand>> commands) {
        int chunkSize = Math.max(1, batchMax);
        for (int from = 0; from < commands.size(); from += chunkSize) {
            processTogether(commands.subList(from, Math.min(from + chunkSize, commands.size())));
        }
    }

    private static int firstUnreadable(List<ConsumerRecord<String, MatchingCommand>> run) {
        for (int i = 0; i < run.size(); i++) {
            if (run.get(i).value() == null) {
                return i;
            }
        }
        return run.size();
    }

    // 영속성 컨텍스트는 트랜잭션마다 새로 열고 닫혀(워커 스레드에 미리 묶인 것이 없다) 따로 비우지 않아도 묶음 사이에 남지 않는다.
    private void processTogether(List<ConsumerRecord<String, MatchingCommand>> batch) {
        long startedAt = System.nanoTime();
        List<CommandResult> results;
        discardIssuedIds(batch.get(0));
        try {
            results = transactionTemplate.execute(status -> handleAll(batch));
        } catch (RuntimeException e) {
            // 한 건짜리를 다시 처리하면 같은 명령을 두 번 시도할 뿐이다. 레코드 리스너처럼 바로 에러 핸들러에 넘긴다.
            if (batch.size() == 1) {
                throw failed(batch.get(0), e);
            }
            log.warn("묶음 처리에 실패해 한 건씩 다시 처리합니다. size={} first={}", batch.size(), describe(batch.get(0)), e);
            metrics.batchFallback();
            processOneByOne(batch);
            return;
        }
        metrics.batch(batch.size());
        long perCommand = (System.nanoTime() - startedAt) / batch.size();
        results.forEach(result -> recordAfterCommit(result, perCommand));
    }

    // 롤백된 묶음은 워터마크도 그대로라 처음부터 다시 처리해도 중복이 없다. 앞서 커밋된 것이 있으면 워터마크가 거른다.
    private void processOneByOne(List<ConsumerRecord<String, MatchingCommand>> batch) {
        discardIssuedIds(batch.get(0));
        for (ConsumerRecord<String, MatchingCommand> record : batch) {
            long startedAt = System.nanoTime();
            List<CommandResult> results;
            try {
                results = transactionTemplate.execute(status -> handleAll(List.of(record)));
            } catch (RuntimeException e) {
                throw failed(record, e);
            }
            metrics.batch(1);
            recordAfterCommit(results.get(0), System.nanoTime() - startedAt);
        }
    }

    private static BatchListenerFailedException failed(ConsumerRecord<String, MatchingCommand> record,
                                                       RuntimeException cause) {
        return new BatchListenerFailedException("체결 명령 처리 실패 " + describe(record), cause, record);
    }

    private List<CommandResult> handleAll(List<ConsumerRecord<String, MatchingCommand>> batch) {
        List<CommandResult> results = new ArrayList<>(batch.size());
        for (ConsumerRecord<String, MatchingCommand> record : batch) {
            results.add(handler.handle(record.value(),
                    new CommandPosition(record.topic(), record.partition(), record.offset())));
            // 다음 명령의 후보 조회는 네이티브 SQL 이라 앞 명령이 바꾼 주문을 DB 에 내려 둬야 본다.
            entityManager.flush();
        }
        // 롤백되면 이 기록도 함께 사라진다. 커밋된 묶음만 마지막 체결 번호를 워터마크에 남긴다.
        ConsumerRecord<String, MatchingCommand> first = batch.get(0);
        OptionalLong lastFillId = fillIdGenerator.takeIssuedSinceLastTake(first.partition());
        if (lastFillId.isPresent()) {
            consumerWatermarkWriter.recordLastFillId(first.topic(), first.partition(), lastFillId.getAsLong());
        }
        return results;
    }

    // 롤백된 묶음이 남긴 번호 기록을 비워, 다음 묶음의 마지막 번호에 섞이지 않게 한다.
    private void discardIssuedIds(ConsumerRecord<String, MatchingCommand> first) {
        fillIdGenerator.takeIssuedSinceLastTake(first.partition());
    }

    // 커밋 전에 세면 롤백 뒤 한 건씩 다시 처리할 때 두 번 센다.
    private void recordAfterCommit(CommandResult result, long nanos) {
        handler.recordMetrics(result);
        metrics.commandTime(result.type(), nanos);
    }

    // 에러 핸들러가 원인을 보고 독 메시지로 분류해 DLT 로 보낸다. 원인이 재시도 대상이면 파티션이 영영 멈춘다.
    private static BatchListenerFailedException unreadable(ConsumerRecord<String, MatchingCommand> record) {
        Exception cause = SerializationUtils.getExceptionFromHeader(record,
                SerializationUtils.VALUE_DESERIALIZER_EXCEPTION_HEADER, DESERIALIZATION_LOG);
        if (cause == null) {
            cause = new InvalidMatchingCommandException("값이 없는 명령입니다. " + describe(record));
        }
        return new BatchListenerFailedException("읽을 수 없는 명령 " + describe(record), cause, record);
    }

    private static String describe(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "@" + record.offset();
    }
}
