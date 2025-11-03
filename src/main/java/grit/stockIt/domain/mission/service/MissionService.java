package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.mission.dto.ClaimRewardRequest;
import grit.stockIt.domain.mission.dto.MissionProgressDto;
import grit.stockIt.domain.mission.dto.MissionResponse;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.mission.entity.MissionType;
import grit.stockIt.domain.mission.entity.UserMissionProgress;
import grit.stockIt.domain.mission.entity.ValidationType;
import grit.stockIt.domain.mission.repository.MissionRepository;
import grit.stockIt.domain.mission.repository.UserMissionProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MissionService {

    private final MissionRepository missionRepository;
    private final UserMissionProgressRepository progressRepository;
    // private final RewardService rewardService; // (가상) 보상 지급을 위한 서비스

    /**
     * 1. 사용자의 모든 미션 목록과 진행도 조회
     */
    @Transactional(readOnly = true)
    public MissionResponse getMissions(Long userId) {
        LocalDateTime now = LocalDateTime.now();

        // 1. 모든 미션 마스터 목록 조회
        List<Mission> allMissions = missionRepository.findAll();

        // 2. 사용자의 현재 유효한 미션 진행도 목록 조회
        List<UserMissionProgress> progressList = progressRepository.findByUserIdAndExpiresAtAfter(userId, now);

        // 3. (성능 최적화) MissionId를 Key로 하는 Map으로 변환
        Map<Long, UserMissionProgress> progressMap = progressList.stream()
                .collect(Collectors.toMap(p -> p.getMission().getMissionId(), p -> p));

        List<MissionProgressDto> dailyDtos = new ArrayList<>();
        List<MissionProgressDto> weeklyDtos = new ArrayList<>();

        // 4. 모든 미션 마스터를 순회하며 DTO 생성
        for (Mission mission : allMissions) {
            UserMissionProgress progress = progressMap.get(mission.getMissionId());

            MissionProgressDto dto;
            if (progress != null) {
                // 진행도가 있으면 있는 데이터로 DTO 생성
                dto = new MissionProgressDto(mission, progress);
            } else {
                // 진행도가 없으면 (아직 시작 안 한 미션) 기본값으로 DTO 생성
                dto = new MissionProgressDto(mission);
            }

            if (mission.getMissionType() == MissionType.DAILY) {
                dailyDtos.add(dto);
            } else {
                weeklyDtos.add(dto);
            }
        }

        return new MissionResponse(dailyDtos, weeklyDtos);
    }

    /**
     * 2. 미션 보상 수령 (일일/주간 공통)
     */
    @Transactional
    public void claimReward(Long userId, ClaimRewardRequest request) {
        LocalDateTime now = LocalDateTime.now();

        Mission mission = missionRepository.findById(request.getMissionId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 미션입니다."));

        // 기존 진행도 조회 (만료되었어도 조회는 함)
        Optional<UserMissionProgress> optProgress = progressRepository.findByUserIdAndMission_MissionId(userId, mission.getMissionId());

        UserMissionProgress progress;

        // 1. CLIENT_ONLY (일일 미션 등)
        if (mission.getValidationType() == ValidationType.CLIENT_ONLY) {
            // 오늘자 진행도가 없거나, 만료되었다면 새로 생성
            if (optProgress.isEmpty() || optProgress.get().getExpiresAt().isBefore(now)) {
                progress = new UserMissionProgress(userId, mission, getExpiryTime(mission.getMissionType()));
            } else {
                progress = optProgress.get();
            }

            if (progress.isRewardClaimed()) {
                throw new IllegalStateException("이미 보상을 수령했습니다.");
            }

            // 클라이언트 요청을 믿고 즉시 완료 및 보상 수령 처리
            progress.setCompleted(true);
            progress.setRewardClaimed(true);
            progress.setCurrentProgress(mission.getRequiredCount());

        }
        // 2. SERVER_PROGRESS (주간 미션 등)
        else {
            progress = optProgress
                    .orElseThrow(() -> new IllegalStateException("미션 진행 기록이 없습니다."));

            if (progress.getExpiresAt().isBefore(now)) {
                throw new IllegalStateException("만료된 미션입니다.");
            }
            if (!progress.isCompleted()) {
                throw new IllegalStateException("미션이 아직 완료되지 않았습니다.");
            }
            if (progress.isRewardClaimed()) {
                throw new IllegalStateException("이미 보상을 수령했습니다.");
            }

            // 상태 변경
            progress.setRewardClaimed(true);
        }

        progressRepository.save(progress);

        // TODO: 보상 지급 로직 호출
        // rewardService.grantReward(userId, mission.getRewardId());
        System.out.println(userId + "님에게 " + mission.getTitle() + " 보상 지급 완료!");
    }

    /**
     * 3. (내부용) 서버 검증 미션 진행도 업데이트
     * (예: TradeService에서 매수 완료 시 이 메소드를 호출)
     */
    @Transactional
    public void updateServerProgress(Long userId, Long missionId) {
        LocalDateTime now = LocalDateTime.now();

        Mission mission = missionRepository.findById(missionId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 미션입니다."));

        // 서버 검증 타입이 아니면 무시
        if (mission.getValidationType() != ValidationType.SERVER_PROGRESS) {
            return;
        }

        // 현재 유효한 진행도 탐색
        Optional<UserMissionProgress> optProgress = progressRepository.findByUserIdAndMission_MissionIdAndExpiresAtAfter(userId, missionId, now);

        UserMissionProgress progress;
        if (optProgress.isEmpty()) {
            // 이번 주 첫 진행 -> 새로 생성
            progress = new UserMissionProgress(userId, mission, getExpiryTime(mission.getMissionType()));
        } else {
            progress = optProgress.get();
        }

        // 이미 완료했으면 더 이상 증가시키지 않음
        if (progress.isCompleted()) {
            return;
        }

        // 진행도 1 증가
        progress.setCurrentProgress(progress.getCurrentProgress() + 1);

        // 목표 횟수 달성 시 완료 처리
        if (progress.getCurrentProgress() >= mission.getRequiredCount()) {
            progress.setCompleted(true);
            // TODO: (선택) 미션 완료 푸시 알림 전송
        }

        progressRepository.save(progress);
    }


    /**
     * (Helper) 미션 타입에 따른 만료 시각 계산
     */
    private LocalDateTime getExpiryTime(MissionType type) {
        if (type == MissionType.DAILY) {
            // 오늘 밤 23:59:59
            return LocalDate.now().atTime(23, 59, 59);
        } else if (type == MissionType.WEEKLY) {
            // 이번 주 일요일 23:59:59
            return LocalDate.now()
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                    .atTime(23, 59, 59);
        }
        throw new IllegalArgumentException("지원하지 않는 미션 타입입니다.");
    }
}