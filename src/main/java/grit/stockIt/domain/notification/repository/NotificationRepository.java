package grit.stockIt.domain.notification.repository;

import grit.stockIt.domain.member.entity.Member;
import grit.stockIt.domain.notification.entity.Notification;
import grit.stockIt.domain.notification.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    // 회원의 알림 목록 조회 (페이징)
    Page<Notification> findByMemberOrderByCreatedAtDesc(Member member, Pageable pageable);

    // 읽지 않은 알림 개수
    long countByMemberAndIsReadFalse(Member member);

    // 읽지 않은 알림 목록
    List<Notification> findByMemberAndIsReadFalseOrderByCreatedAtDesc(Member member);

    // 특정 타입의 알림 조회
    List<Notification> findByMemberAndTypeOrderByCreatedAtDesc(Member member, NotificationType type);

    // 회원의 모든 알림 개수
    long countByMember(Member member);
}

