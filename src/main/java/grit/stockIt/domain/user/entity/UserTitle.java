package grit.stockIt.domain.user.entity;

import grit.stockIt.domain.title.entity.Title;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class UserTitle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "title_id")
    private Title title;

    private LocalDateTime acquiredAt;

    // 생성자
    public UserTitle(User user, Title title) {
        this.user = user;
        this.title = title;
        this.acquiredAt = LocalDateTime.now();
    }

    // 연관관계 메서드
    public void setUser(User user) {
        this.user = user;
        user.getUserTitles().add(this);
    }
}