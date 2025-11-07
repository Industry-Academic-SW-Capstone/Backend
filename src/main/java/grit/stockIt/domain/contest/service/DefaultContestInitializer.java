package grit.stockIt.domain.contest.service;

import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultContestInitializer implements ApplicationRunner {

    private final ContestRepository contestRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
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
                .seedMoney(1_000_000L)
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