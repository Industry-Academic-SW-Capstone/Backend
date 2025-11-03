package grit.stockIt.domain.reward.service;

import grit.stockIt.domain.reward.entity.Reward;
import grit.stockIt.domain.title.entity.Title;
import grit.stockIt.domain.user.entity.User;
import grit.stockIt.domain.user.entity.UserTitle;
import grit.stockIt.domain.title.repository.TitleRepository;
import grit.stockIt.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RewardService {
    private final UserRepository userRepository;
    private final TitleRepository titleRepository;

    @Transactional
    public void giveReward(User user, Reward reward) {
        if (reward.isHasTitle()) {
            giveTitle(user, reward.getTitleName());
        }

        if (reward.isHasMoney()) {
            giveMoney(user, reward.getAmount());
        }
    }

    private void giveTitle(User user, String titleName) {
        Title title = titleRepository.findByName(titleName)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 칭호입니다: " + titleName));

        // 이미 가지고 있는 칭호인지 확인
        boolean alreadyHas = user.getUserTitles().stream()
                .anyMatch(userTitle -> userTitle.getTitle().getName().equals(titleName));

        if (!alreadyHas) {
            UserTitle userTitle = new UserTitle(user, title);
            user.getUserTitles().add(userTitle);
            // 첫 칭호인 경우 현재 칭호로 설정
            if (user.getCurrentTitle() == null) {
                user.changeCurrentTitle(userTitle);
            }
        }
    }

    private void giveMoney(User user, Long amount) {
        user.addMoney(amount);
        userRepository.save(user);
    }
}