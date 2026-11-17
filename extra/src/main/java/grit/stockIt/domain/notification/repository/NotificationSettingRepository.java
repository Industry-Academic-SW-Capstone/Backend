package grit.stockIt.domain.notification.repository;

import grit.stockIt.domain.notification.entity.NotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {

    List<NotificationSetting> findAllByFcmToken(String fcmToken);

    List<NotificationSetting> findAllByFcmTokenIsNotNull();
}
