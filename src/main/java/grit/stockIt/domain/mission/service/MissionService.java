package grit.stockIt.domain.mission.service;

import grit.stockIt.domain.mission.dto.MissionProgressDto;
import grit.stockIt.domain.mission.dto.MissionResponse;
import grit.stockIt.domain.mission.dto.ClaimRewardRequest;
import grit.stockIt.domain.mission.entity.Mission;
import grit.stockIt.domain.user.entity.UserMission;
import grit.stockIt.domain.mission.repository.MissionRepository;
import grit.stockIt.domain.user.repository.UserMissionRepository;
import grit.stockIt.domain.reward.service.RewardService;
import grit.stockIt.domain.user.entity.User;
import grit.stockIt.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MissionService {
    private final MissionRepository missionRepository;
    private final UserMissionRepository userMissionRepository;
    private final UserRepository userRepository;
    private final RewardService rewardService;

    @Transactional
    public MissionResponse getMissions(Long userId) {
        User user = findUser(userId);
        List<UserMission> userMissions = userMissionRepository.findByUser(user);
        List<Mission> availableMissions = missionRepository.findByIsActiveTrue();

        createMissingUserMissions(user, userMissions, availableMissions);

        List<MissionProgressDto> dailyMissions = userMissions.stream()
                .filter(um -> um.getMission().isDaily())
                .map(this::convertToMissionProgressDto)
                .collect(Collectors.toList());

        List<MissionProgressDto> weeklyMissions = userMissions.stream()
                .filter(um -> !um.getMission().isDaily())
                .map(this::convertToMissionProgressDto)
                .collect(Collectors.toList());

        return new MissionResponse(dailyMissions, weeklyMissions);
    }

    @Transactional
    public void updateMissionProgress(Long userId, Long missionId) {
        UserMission userMission = getUserMission(userId, missionId);

        if (!userMission.isCompleted()) {
            userMission.updateProgress();
            if (userMission.isCompleted()) {
                userMission.updateProgress();
            }
        }
    }

    @Transactional
    public void claimReward(Long userId, ClaimRewardRequest request) {
        UserMission userMission = getUserMission(userId, request.getMissionId());

        if (!userMission.isCompleted()) {
            throw new IllegalStateException("아직 완료되지 않은 미션입니다.");
        }

        if (userMission.getStatus() == UserMission.MissionStatus.REWARD_CLAIMED) {
            throw new IllegalStateException("이미 보상을 수령한 미션입니다.");
        }

        rewardService.giveReward(userMission.getUser(), userMission.getMission().getReward());
        userMission.setRewardClaimed();
    }


    @Transactional
    public void restartMission(Long userId, Long missionId) {
        User user = findUser(userId);
        Mission mission = findMission(missionId);

        userMissionRepository.findByUserAndMission(user, mission)
                .ifPresent(userMissionRepository::delete);

        UserMission newUserMission = new UserMission(user, mission);
        userMissionRepository.save(newUserMission);
    }

    private MissionProgressDto convertToMissionProgressDto(UserMission userMission) {
        return new MissionProgressDto(userMission.getMission(), userMission);
    }



    @Transactional
    private void createMissingUserMissions(User user, List<UserMission> existingMissions, List<Mission> availableMissions) {
        availableMissions.stream()
                .filter(mission -> existingMissions.stream()
                        .noneMatch(um -> um.getMission().equals(mission)))
                .forEach(mission -> {
                    UserMission newUserMission = new UserMission(user, mission);
                    userMissionRepository.save(newUserMission);
                    existingMissions.add(newUserMission);
                });
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
    }

    private Mission findMission(Long missionId) {
        return missionRepository.findById(missionId)
                .orElseThrow(() -> new IllegalArgumentException("미션을 찾을 수 없습니다."));
    }

    private UserMission getUserMission(Long userId, Long missionId) {
        User user = findUser(userId);
        Mission mission = findMission(missionId);

        return userMissionRepository.findByUserAndMission(user, mission)
                .orElseThrow(() -> new IllegalArgumentException("해당 사용자의 미션을 찾을 수 없습니다."));
    }
}