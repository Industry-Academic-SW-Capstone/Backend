package grit.stockIt.domain.contest.service;

import grit.stockIt.domain.contest.dto.ContestCreateRequest;
import grit.stockIt.domain.contest.dto.ContestResponse;
import grit.stockIt.domain.contest.dto.ContestUpdateRequest;
import grit.stockIt.domain.contest.entity.Contest;
import grit.stockIt.domain.contest.repository.ContestRepository;
import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.member.repository.MemberRepository;
import grit.stockIt.global.exception.BadRequestException;
import grit.stockIt.global.exception.ForbiddenException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ContestService {

    private final ContestRepository contestRepository;
    private final MemberRepository memberRepository;

    /**
     * 대회 생성
     */
    @Transactional
    public ContestResponse createContest(ContestCreateRequest request, String userEmail) {
        Member member = memberRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BadRequestException("존재하지 않는 회원입니다."));
        
        Contest contest = Contest.builder()
                .contestName(request.getContestName())
                .isDefault(false)
                .managerMemberId(member.getMemberId())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .seedMoney(request.getSeedMoney())
                .commissionRate(request.getCommissionRate())
                .minMarketCap(request.getMinMarketCap())
                .maxMarketCap(request.getMaxMarketCap())
                .dailyTradeLimit(request.getDailyTradeLimit())
                .maxHoldingsCount(request.getMaxHoldingsCount())
                .buyCooldownMinutes(request.getBuyCooldownMinutes())
                .sellCooldownMinutes(request.getSellCooldownMinutes())
                .build();

        Contest saved = contestRepository.save(contest);
        log.info("Contest created: id={}, name={}, manager={}", saved.getContestId(), saved.getContestName(), member.getMemberId());
        
        return ContestResponse.from(saved);
    }

    /**
     * 대회 목록 조회 (기본 대회 제외)
     */
    public List<ContestResponse> getAllContests() {
        return contestRepository.findAllByIsDefaultFalse().stream()
                .map(ContestResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * 대회 단건 조회
     */
    public ContestResponse getContest(Long contestId) {
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new BadRequestException("존재하지 않는 대회입니다."));
        return ContestResponse.from(contest);
    }

    /**
     * 대회 수정
     */
    @Transactional
    public ContestResponse updateContest(Long contestId, ContestUpdateRequest request, String userEmail) {
        Member member = memberRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BadRequestException("존재하지 않는 회원입니다."));
        
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new BadRequestException("존재하지 않는 대회입니다."));

        // 기본 대회는 수정 불가
        if (contest.getIsDefault()) {
            throw new BadRequestException("기본 대회는 수정할 수 없습니다.");
        }

        // 권한 검증: 방장만 수정 가능
        if (!contest.getManagerMemberId().equals(member.getMemberId())) {
            throw new ForbiddenException("대회를 수정할 권한이 없습니다.");
        }

        // 엔티티의 update 메소드 사용
        contest.update(request);

        log.info("Contest updated: id={}, name={}", contestId, contest.getContestName());
        return ContestResponse.from(contest);
    }

    /**
     * 대회 삭제 (소프트 삭제)
     */
    @Transactional
    public void deleteContest(Long contestId, String userEmail) {
        Member member = memberRepository.findByEmail(userEmail)
                .orElseThrow(() -> new BadRequestException("존재하지 않는 회원입니다."));
        
        Contest contest = contestRepository.findById(contestId)
                .orElseThrow(() -> new BadRequestException("존재하지 않는 대회입니다."));

        // 기본 대회는 삭제 불가
        if (contest.getIsDefault()) {
            throw new BadRequestException("기본 대회는 삭제할 수 없습니다.");
        }

        // 권한 검증: 방장만 삭제 가능
        if (!contest.getManagerMemberId().equals(member.getMemberId())) {
            throw new ForbiddenException("대회를 삭제할 권한이 없습니다.");
        }

        // 소프트 삭제: deletedAt 설정
        contest.softDelete();
        log.info("Contest soft deleted: id={}, name={}", contestId, contest.getContestName());
    }
}
