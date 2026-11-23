package grit.stockIt.domain.contest.service;

import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// Kafka 리스너와 웹 서버가 뜨기 전에 돈다. 재시작 때 밀린 메시지와 요청이 연결 풀을 먼저 차지해 기동이 실패하지 않게 한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultContestInitializer implements SmartInitializingSingleton {

    private final ContestRepository contestRepository;

    @Override
    public void afterSingletonsInstantiated() {
        ensureDefaultContest();
    }

    public void ensureDefaultContest() {
        boolean exists = contestRepository.findByIsDefaultTrue().isPresent();
        if (exists) {
            log.info("Default contest already exists.");
            return;
        }

        // 기본값은 필요에 맞게 조정하세요.
        Contest defaultContest = Contest.builder()
                .contestName("Practice Market")
                .isDefault(true)
                .startDate(LocalDateTime.now())
                .endDate(null)
                .seedMoney(10_000_000L)
                .commissionRate(new BigDecimal("0.0000"))
                .minMarketCap(null)
                .maxMarketCap(null)
                .dailyTradeLimit(null)
                .maxHoldingsCount(null)
                .buyCooldownMinutes(null)
                .sellCooldownMinutes(null)
                .build();

        contestRepository.save(defaultContest);
        log.info("Default contest created with id={}", defaultContest.getContestId());
    }
}