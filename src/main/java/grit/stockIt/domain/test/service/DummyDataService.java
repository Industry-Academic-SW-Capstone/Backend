package grit.stockIt.domain.test.service;

import grit.stockIt.domain.account.entity.Account;
import grit.stockIt.domain.account.repository.AccountRepository;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.member.entity.AuthProvider;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.domain.test.dto.DummyDataResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 더미 데이터 생성 서비스
 * - 성능 테스트를 위한 더미 회원 및 계좌 생성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DummyDataService {

    private final MemberRepository memberRepository;
    private final AccountRepository accountRepository;
    private final ContestRepository contestRepository;

    private static final String DUMMY_EMAIL_PREFIX = "test_user_";
    private static final String DUMMY_NAME_PREFIX = "테스트유저";
    private static final Random random = new Random();

    /**
     * 더미 데이터 생성
     * - 회원 및 Main 계좌 생성
     * - 각 회원은 랜덤한 잔액을 가진 Main 계좌 소유
     *
     * @param memberCount 생성할 회원 수
     * @return DummyDataResponse
     */
    @Transactional
    public DummyDataResponse generateDummyData(int memberCount) {
        long startTime = System.currentTimeMillis();
        
        log.info("🚀 [더미 데이터 생성 시작] 회원 수: {}", memberCount);

        // Default Contest 조회
        Contest defaultContest = contestRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("Default Contest를 찾을 수 없습니다."));
        
        log.info("✅ Default Contest: {} (ID: {})", defaultContest.getContestName(), defaultContest.getContestId());

        List<Member> members = new ArrayList<>();
        List<Account> accounts = new ArrayList<>();
        
        BigDecimal minBalance = BigDecimal.valueOf(100_000); // 10만원
        BigDecimal maxBalance = BigDecimal.valueOf(10_000_000); // 1000만원
        BigDecimal totalBalance = BigDecimal.ZERO;

        // 회원 및 계좌 생성
        for (int i = 1; i <= memberCount; i++) {
            // 1. 회원 생성
            String email = DUMMY_EMAIL_PREFIX + i + "@test.com";
            String name = DUMMY_NAME_PREFIX + i;
            
            // 이미 존재하는 회원은 스킵
            if (memberRepository.existsByEmail(email)) {
                log.debug("회원 [{}] 이미 존재함 - 스킵", email);
                continue;
            }
            
            Member member = Member.builder()
                    .email(email)
                    .name(name)
                    .provider(AuthProvider.LOCAL)
                    .password("test1234") // 더미 비밀번호
                    .executionNotificationEnabled(true)
                    .build();
            
            members.add(member);
            
            // 2. Main 계좌 생성 (랜덤 잔액)
            BigDecimal randomBalance = generateRandomBalance(minBalance, maxBalance);
            totalBalance = totalBalance.add(randomBalance);
            
            Account account = Account.builder()
                    .member(member)
                    .contest(defaultContest)
                    .accountName(name + " Main 계좌")
                    .cash(randomBalance)
                    .isDefault(true)
                    .build();
            
            accounts.add(account);
        }

        // 3. Batch Insert
        log.info("💾 [DB 저장 시작] 회원: {}명, 계좌: {}개", members.size(), accounts.size());
        memberRepository.saveAll(members);
        accountRepository.saveAll(accounts);
        log.info("✅ [DB 저장 완료]");

        // 4. 통계 계산
        BigDecimal avgBalance = members.isEmpty() 
                ? BigDecimal.ZERO 
                : totalBalance.divide(BigDecimal.valueOf(members.size()), 2, RoundingMode.HALF_UP);

        long elapsedTime = System.currentTimeMillis() - startTime;
        
        log.info("🎉 [더미 데이터 생성 완료] 회원: {}명, 계좌: {}개, 소요 시간: {}ms",
                members.size(), accounts.size(), elapsedTime);

        return DummyDataResponse.builder()
                .memberCount(members.size())
                .accountCount(accounts.size())
                .minBalance(minBalance)
                .maxBalance(maxBalance)
                .avgBalance(avgBalance)
                .elapsedTimeMs(elapsedTime)
                .message(String.format("✅ 더미 데이터 생성 완료! 회원 %d명, 계좌 %d개 생성됨", members.size(), accounts.size()))
                .build();
    }

    /**
     * 더미 데이터 삭제
     * - test_user_ 로 시작하는 이메일을 가진 회원 및 계좌 삭제
     *
     * @return 삭제된 회원 수
     */
    @Transactional
    public int clearDummyData() {
        log.info("🗑️ [더미 데이터 삭제 시작]");

        // 1. test_user_ 로 시작하는 이메일 조회
        List<Member> dummyMembers = memberRepository.findAll().stream()
                .filter(member -> member.getEmail().startsWith(DUMMY_EMAIL_PREFIX))
                .toList();

        if (dummyMembers.isEmpty()) {
            log.info("삭제할 더미 데이터가 없습니다.");
            return 0;
        }

        // 2. 회원 삭제 (CascadeType으로 계좌도 함께 삭제됨)
        memberRepository.deleteAll(dummyMembers);

        log.info("✅ [더미 데이터 삭제 완료] 회원: {}명 삭제", dummyMembers.size());

        return dummyMembers.size();
    }

    /**
     * 더미 데이터 통계 조회
     *
     * @return DummyDataResponse
     */
    @Transactional(readOnly = true)
    public DummyDataResponse getDummyDataStats() {
        log.info("📊 [더미 데이터 통계 조회]");

        // 1. test_user_ 로 시작하는 회원 조회
        List<Member> dummyMembers = memberRepository.findAll().stream()
                .filter(member -> member.getEmail().startsWith(DUMMY_EMAIL_PREFIX))
                .toList();

        if (dummyMembers.isEmpty()) {
            return DummyDataResponse.builder()
                    .memberCount(0)
                    .accountCount(0)
                    .message("생성된 더미 데이터가 없습니다.")
                    .build();
        }

        // 2. 각 회원의 Main 계좌 조회
        Contest defaultContest = contestRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("Default Contest를 찾을 수 없습니다."));

        List<Account> accounts = dummyMembers.stream()
                .map(member -> accountRepository.findByMemberAndContest(member, defaultContest))
                .filter(opt -> opt.isPresent())
                .map(opt -> opt.get())
                .toList();

        // 3. 잔액 통계
        BigDecimal minBalance = accounts.stream()
                .map(Account::getCash)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal maxBalance = accounts.stream()
                .map(Account::getCash)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal totalBalance = accounts.stream()
                .map(Account::getCash)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal avgBalance = accounts.isEmpty()
                ? BigDecimal.ZERO
                : totalBalance.divide(BigDecimal.valueOf(accounts.size()), 2, RoundingMode.HALF_UP);

        return DummyDataResponse.builder()
                .memberCount(dummyMembers.size())
                .accountCount(accounts.size())
                .minBalance(minBalance)
                .maxBalance(maxBalance)
                .avgBalance(avgBalance)
                .message(String.format("📊 더미 데이터 통계: 회원 %d명, 계좌 %d개", dummyMembers.size(), accounts.size()))
                .build();
    }

    /**
     * 랜덤 잔액 생성
     * - min ~ max 범위 내에서 랜덤 잔액 생성
     *
     * @param min 최소 잔액
     * @param max 최대 잔액
     * @return 랜덤 잔액
     */
    private BigDecimal generateRandomBalance(BigDecimal min, BigDecimal max) {
        long minLong = min.longValue();
        long maxLong = max.longValue();
        long randomLong = minLong + (long) (random.nextDouble() * (maxLong - minLong));
        
        // 10000원 단위로 절사
        randomLong = (randomLong / 10000) * 10000;
        
        return BigDecimal.valueOf(randomLong);
    }
}

